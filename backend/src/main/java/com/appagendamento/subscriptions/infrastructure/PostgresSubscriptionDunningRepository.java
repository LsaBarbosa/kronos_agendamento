package com.appagendamento.subscriptions.infrastructure;

import com.appagendamento.appointments.api.TenantAgendaLock;
import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.audit.api.AuditEvent;
import com.appagendamento.audit.api.AuditEventRecorder;
import com.appagendamento.outbox.api.OutboxEvent;
import com.appagendamento.outbox.api.OutboxEventRecorder;
import com.appagendamento.subscriptions.application.AnnualRenewalAuthorization;
import com.appagendamento.subscriptions.application.DunningChargeAuthorization;
import com.appagendamento.subscriptions.application.DunningChargeTransport;
import com.appagendamento.subscriptions.application.DunningMetricsSnapshot;
import com.appagendamento.subscriptions.application.SubscriptionDunningRepository;
import com.appagendamento.subscriptions.domain.AnnualRenewalNoticeView;
import com.appagendamento.subscriptions.domain.DunningAttemptClaim;
import com.appagendamento.subscriptions.domain.DunningAttemptView;
import com.appagendamento.subscriptions.domain.Subscription;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PostgresSubscriptionDunningRepository implements SubscriptionDunningRepository {

  private static final RowMapper<Subscription> SUBSCRIPTION_MAPPER =
      PostgresSubscriptionDunningRepository::mapSubscription;
  private final JdbcTemplate jdbc;
  private final AuditEventRecorder audit;
  private final OutboxEventRecorder outbox;
  private final AuditCorrelationId correlation;
  private final TenantAgendaLock agendaLock;
  private final Clock clock;
  private final DunningChargeAuthorization chargeAuthorization;
  private final AnnualRenewalAuthorization annualAuthorization;

  public PostgresSubscriptionDunningRepository(
      JdbcTemplate jdbc,
      AuditEventRecorder audit,
      OutboxEventRecorder outbox,
      AuditCorrelationId correlation,
      TenantAgendaLock agendaLock,
      Clock clock,
      DunningChargeAuthorization chargeAuthorization,
      AnnualRenewalAuthorization annualAuthorization) {
    this.jdbc = jdbc;
    this.audit = audit;
    this.outbox = outbox;
    this.correlation = correlation;
    this.agendaLock = agendaLock;
    this.clock = clock;
    this.chargeAuthorization = chargeAuthorization;
    this.annualAuthorization = annualAuthorization;
  }

  @Override
  @Transactional(readOnly = true)
  public List<Subscription> findDueActive(Instant asOf, int limit) {
    return jdbc.query(
        """
        SELECT subscription_id, tenant_id, status, trial_started_at, trial_ends_at,
               plan_code, paid_through_at, grace_ends_at, version
        FROM agendamento.subscription
        WHERE status = 'ACTIVE' AND paid_through_at <= ?
        ORDER BY paid_through_at, tenant_id LIMIT ?
        """,
        SUBSCRIPTION_MAPPER,
        Timestamp.from(asOf),
        limit);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Subscription> findGraceElapsed(Instant asOf, int limit) {
    return jdbc.query(
        """
        SELECT subscription_id, tenant_id, status, trial_started_at, trial_ends_at,
               plan_code, paid_through_at, grace_ends_at, version
        FROM agendamento.subscription
        WHERE status = 'PAST_DUE' AND grace_ends_at <= ?
        ORDER BY grace_ends_at, tenant_id LIMIT ?
        """,
        SUBSCRIPTION_MAPPER,
        Timestamp.from(asOf),
        limit);
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public boolean markPastDue(Subscription expected, Instant asOf) {
    List<DueState> changed =
        jdbc.query(
            """
            UPDATE agendamento.subscription
            SET status = 'PAST_DUE', grace_ends_at = paid_through_at + INTERVAL '168 hours',
                version = version + 1
            WHERE tenant_id = ? AND subscription_id = ? AND status = 'ACTIVE'
              AND version = ? AND paid_through_at <= ?
            RETURNING subscription_id, tenant_id, paid_through_at, grace_ends_at, version
            """,
            (row, index) ->
                new DueState(
                    row.getObject("subscription_id", UUID.class),
                    row.getObject("tenant_id", UUID.class),
                    row.getTimestamp("paid_through_at").toInstant(),
                    row.getTimestamp("grace_ends_at").toInstant(),
                    row.getLong("version")),
            expected.tenantId(),
            expected.subscriptionId(),
            expected.version(),
            Timestamp.from(asOf));
    if (changed.isEmpty()) return false;

    DueState due = changed.getFirst();
    UUID operationId = operationId(due.subscriptionId(), "past-due", due.version());
    UUID cycleId =
        UUID.nameUUIDFromBytes(
            (due.subscriptionId() + ":dunning:" + due.dueAt()).getBytes(StandardCharsets.UTF_8));
    jdbc.update(
        """
        INSERT INTO agendamento.subscription_history
          (event_id, tenant_id, subscription_id, event_type, from_status, to_status,
           event_at, version, operation_id)
        VALUES (?, ?, ?, 'PAYMENT_PAST_DUE', 'ACTIVE', 'PAST_DUE', ?, ?, ?)
        """,
        UUID.randomUUID(),
        due.tenantId(),
        due.subscriptionId(),
        Timestamp.from(asOf),
        due.version(),
        operationId);
    jdbc.update(
        """
        INSERT INTO agendamento.billing_dunning_cycle
          (cycle_id, tenant_id, subscription_id, due_at, grace_ends_at, state, created_at)
        VALUES (?, ?, ?, ?, ?, 'OPEN', ?)
        """,
        cycleId,
        due.tenantId(),
        due.subscriptionId(),
        Timestamp.from(due.dueAt()),
        Timestamp.from(due.graceEndsAt()),
        Timestamp.from(asOf));
    ContractTerms contractTerms =
        jdbc
            .query(
                """
                SELECT amount_minor_units, currency, terms_version, offer_version, plan_code
                FROM agendamento.billing_subscription_contract
                WHERE tenant_id = ? AND subscription_id = ?
                """,
                (row, index) ->
                    new ContractTerms(
                        row.getObject("amount_minor_units", Long.class),
                        row.getString("currency"),
                        row.getString("terms_version"),
                        row.getString("offer_version"),
                        row.getString("plan_code")),
                due.tenantId(),
                due.subscriptionId())
            .stream()
            .findFirst()
            .orElse(null);
    for (int sequence : List.of(0, 3, 6)) {
      UUID attemptId =
          UUID.nameUUIDFromBytes(
              (cycleId + ":logical-attempt:" + sequence).getBytes(StandardCharsets.UTF_8));
      jdbc.update(
          """
          INSERT INTO agendamento.billing_dunning_attempt
            (attempt_id, tenant_id, cycle_id, logical_sequence, scheduled_at, state, updated_at,
             external_idempotency_key, contract_amount_minor_units, contract_currency,
             contract_terms_version, contract_offer_version, contract_plan_code)
          VALUES (?, ?, ?, ?, ?, 'SCHEDULED', ?, ?, ?, ?, ?, ?, ?)
          """,
          attemptId,
          due.tenantId(),
          cycleId,
          sequence,
          Timestamp.from(due.dueAt().plus(Duration.ofHours(24L * sequence))),
          Timestamp.from(asOf),
          attemptId,
          contractTerms == null ? null : contractTerms.amountMinorUnits(),
          contractTerms == null ? null : contractTerms.currency(),
          contractTerms == null ? null : contractTerms.termsVersion(),
          contractTerms == null ? null : contractTerms.offerVersion(),
          contractTerms == null ? null : contractTerms.planCode());
    }
    recordEffects(
        due.tenantId(),
        due.subscriptionId(),
        operationId,
        "SUBSCRIPTION_PAYMENT_PAST_DUE",
        "SubscriptionPastDue",
        due.version() - 1,
        due.version());
    return true;
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public boolean suspendPastDue(Subscription expected, Instant asOf) {
    int changed =
        jdbc.update(
            """
            UPDATE agendamento.subscription
            SET status = 'SUSPENDED', version = version + 1
            WHERE tenant_id = ? AND subscription_id = ? AND status = 'PAST_DUE'
              AND version = ? AND grace_ends_at <= ?
            """,
            expected.tenantId(),
            expected.subscriptionId(),
            expected.version(),
            Timestamp.from(asOf));
    if (changed != 1) return false;
    long version = expected.version() + 1;
    UUID operationId = operationId(expected.subscriptionId(), "suspended", version);
    jdbc.update(
        """
        INSERT INTO agendamento.subscription_history
          (event_id, tenant_id, subscription_id, event_type, from_status, to_status,
           event_at, version, operation_id)
        VALUES (?, ?, ?, 'PAYMENT_SUSPENDED', 'PAST_DUE', 'SUSPENDED', ?, ?, ?)
        """,
        UUID.randomUUID(),
        expected.tenantId(),
        expected.subscriptionId(),
        Timestamp.from(asOf),
        version,
        operationId);
    jdbc.update(
        """
        UPDATE agendamento.billing_dunning_cycle
        SET state = 'SUSPENDED', closed_at = ?
        WHERE tenant_id = ? AND subscription_id = ? AND state IN ('OPEN', 'SUSPENDED')
        """,
        Timestamp.from(asOf),
        expected.tenantId(),
        expected.subscriptionId());
    jdbc.update(
        """
        UPDATE agendamento.billing_dunning_attempt
        SET state = 'CANCELLED', claim_token = NULL, lease_until = NULL,
            last_error_code = 'SUBSCRIPTION_SUSPENDED', updated_at = ?
        WHERE tenant_id = ? AND state IN (
            'SCHEDULED', 'BLOCKED_CONFIGURATION', 'CLAIMED', 'RETRYABLE_NO_EFFECT')
          AND cycle_id IN (SELECT cycle_id FROM agendamento.billing_dunning_cycle
                           WHERE tenant_id = ? AND subscription_id = ?)
        """,
        Timestamp.from(asOf),
        expected.tenantId(),
        expected.tenantId(),
        expected.subscriptionId());
    recordEffects(
        expected.tenantId(),
        expected.subscriptionId(),
        operationId,
        "SUBSCRIPTION_PAYMENT_SUSPENDED",
        "SubscriptionPaymentSuspended",
        expected.version(),
        version);
    return true;
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void closeCyclesForPayment(UUID tenantId, UUID subscriptionId, Instant paidAt) {
    jdbc.update(
        """
        UPDATE agendamento.billing_dunning_cycle SET state = 'PAID', closed_at = ?
        WHERE tenant_id = ? AND subscription_id = ? AND state IN ('OPEN', 'SUSPENDED')
        """,
        Timestamp.from(paidAt),
        tenantId,
        subscriptionId);
    jdbc.update(
        """
        UPDATE agendamento.billing_dunning_attempt
        SET state = 'CANCELLED', claim_token = NULL, lease_until = NULL,
            last_error_code = 'PAYMENT_CONFIRMED', updated_at = ?
        WHERE tenant_id = ? AND state IN (
            'SCHEDULED', 'BLOCKED_CONFIGURATION', 'CLAIMED', 'RETRYABLE_NO_EFFECT')
          AND cycle_id IN (SELECT cycle_id FROM agendamento.billing_dunning_cycle
                           WHERE tenant_id = ? AND subscription_id = ?)
        """,
        Timestamp.from(paidAt),
        tenantId,
        tenantId,
        subscriptionId);
  }

  @Override
  @Transactional(readOnly = true)
  public boolean hasUnresolvedPayment(UUID tenantId, UUID subscriptionId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
            SELECT EXISTS (
              SELECT 1
              FROM agendamento.billing_dunning_attempt attempt
              JOIN agendamento.billing_dunning_cycle cycle USING (cycle_id, tenant_id)
              WHERE attempt.tenant_id = ? AND cycle.subscription_id = ?
                AND attempt.state IN ('CLAIMED', 'SUBMITTING', 'OUTCOME_UNKNOWN', 'RECONCILING', 'ACCEPTED')
            )
            """,
            Boolean.class,
            tenantId,
            subscriptionId));
  }

  @Override
  @Transactional(readOnly = true)
  public List<DunningAttemptView> listAttempts(UUID tenantId) {
    return jdbc.query(
        """
        SELECT a.logical_sequence, a.scheduled_at, a.state, a.next_attempt_at,
               a.technical_attempts, a.last_error_code, a.skip_reason
        FROM agendamento.billing_dunning_attempt a
        JOIN agendamento.billing_dunning_cycle c USING (cycle_id, tenant_id)
        WHERE tenant_id = ?
        ORDER BY c.due_at DESC, a.logical_sequence
        LIMIT 30
        """,
        (row, index) ->
            new DunningAttemptView(
                row.getInt("logical_sequence"),
                row.getTimestamp("scheduled_at").toInstant(),
                row.getString("state"),
                instant(row, "next_attempt_at"),
                row.getInt("technical_attempts"),
                row.getString("last_error_code"),
                row.getString("skip_reason")),
        tenantId);
  }

  @Override
  @Transactional(readOnly = true)
  public List<AnnualRenewalNoticeView> listAnnualRenewalNotices(UUID tenantId) {
    return jdbc.query(
        """
        SELECT notice_id, subscription_version, paid_through_at, scheduled_at, state, obsolete_reason
        FROM agendamento.billing_annual_renewal_notice
        WHERE tenant_id = ?
        ORDER BY paid_through_at DESC, notice_id DESC
        LIMIT 12
        """,
        (row, index) ->
            new AnnualRenewalNoticeView(
                row.getObject("notice_id", UUID.class),
                row.getLong("subscription_version"),
                row.getTimestamp("paid_through_at").toInstant(),
                row.getTimestamp("scheduled_at").toInstant(),
                row.getString("state"),
                row.getString("obsolete_reason")),
        tenantId);
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public int materializeAnnualRenewalNotices(Instant asOf, int limit) {
    if (asOf == null || limit < 1 || limit > 500) {
      throw new IllegalArgumentException("Parâmetros de aviso anual inválidos");
    }
    List<UUID> tenants =
        jdbc.query(
            """
            SELECT tenant_id FROM (
              SELECT subscription.tenant_id,
                     subscription.paid_through_at - INTERVAL '168 hours' AS priority_at
              FROM agendamento.subscription subscription
              WHERE subscription.plan_code = 'ANNUAL'
                AND subscription.status IN ('ACTIVE', 'PAST_DUE', 'SUSPENDED')
                AND NOT EXISTS (
                  SELECT 1 FROM agendamento.billing_annual_renewal_notice notice
                  WHERE notice.subscription_id = subscription.subscription_id
                    AND notice.paid_through_at = subscription.paid_through_at
                )
              UNION
              SELECT notice.tenant_id, notice.scheduled_at AS priority_at
              FROM agendamento.billing_annual_renewal_notice notice
              LEFT JOIN agendamento.subscription subscription
                ON subscription.tenant_id = notice.tenant_id
               AND subscription.subscription_id = notice.subscription_id
              WHERE notice.state IN ('SCHEDULED', 'READY')
                AND (notice.scheduled_at <= ? OR notice.paid_through_at <= ?
                  OR subscription.subscription_id IS NULL
                  OR subscription.plan_code <> 'ANNUAL'
                  OR subscription.status <> 'ACTIVE'
                  OR subscription.paid_through_at <> notice.paid_through_at)
            ) candidates
            GROUP BY tenant_id
            ORDER BY min(priority_at), tenant_id
            LIMIT ?
            """,
            (row, index) -> row.getObject("tenant_id", UUID.class),
            Timestamp.from(asOf),
            Timestamp.from(asOf),
            limit);
    int changed = 0;
    for (UUID tenantId : tenants) {
      agendaLock.acquire(tenantId);
      Instant now = clock.instant();
      List<AnnualSubscriptionRow> subscriptions =
          jdbc.query(
              """
              SELECT subscription_id, version, paid_through_at, status, plan_code
              FROM agendamento.subscription
              WHERE tenant_id = ? AND status IN ('ACTIVE', 'PAST_DUE', 'SUSPENDED')
                AND plan_code = 'ANNUAL'
              FOR UPDATE
              """,
              (row, index) ->
                  new AnnualSubscriptionRow(
                      row.getObject("subscription_id", UUID.class),
                      row.getLong("version"),
                      row.getTimestamp("paid_through_at").toInstant(),
                      row.getString("status"),
                      row.getString("plan_code")),
              tenantId);
      AnnualSubscriptionRow subscription =
          subscriptions.isEmpty() ? null : subscriptions.getFirst();
      List<AnnualNoticeRow> pendingNotices =
          jdbc.query(
              """
              SELECT notice_id, subscription_id, subscription_version, paid_through_at,
                     scheduled_at, state
              FROM agendamento.billing_annual_renewal_notice
              WHERE tenant_id = ? AND state IN ('SCHEDULED', 'READY')
              ORDER BY paid_through_at, notice_id
              FOR UPDATE
              """,
              (row, index) ->
                  new AnnualNoticeRow(
                      row.getObject("notice_id", UUID.class),
                      row.getObject("subscription_id", UUID.class),
                      row.getLong("subscription_version"),
                      row.getTimestamp("paid_through_at").toInstant(),
                      row.getTimestamp("scheduled_at").toInstant(),
                      row.getString("state")),
              tenantId);
      for (AnnualNoticeRow notice : pendingNotices) {
        String reason = null;
        if (subscription == null || !subscription.planCode().equals("ANNUAL")) {
          reason = "SUBSCRIPTION_INELIGIBLE";
        } else if (!subscription.paidThroughAt().equals(notice.paidThroughAt())) {
          reason = "PERIOD_CHANGED";
        } else if (!now.isBefore(notice.paidThroughAt())) {
          reason = "PAID_PERIOD_EXPIRED";
        } else if (!subscription.status().equals("ACTIVE")) {
          reason = "SUBSCRIPTION_INELIGIBLE";
        }
        if (reason != null) {
          changed +=
              jdbc.update(
                  """
                  UPDATE agendamento.billing_annual_renewal_notice
                  SET state = 'OBSOLETE', obsolete_reason = ?, updated_at = ?
                  WHERE tenant_id = ? AND notice_id = ? AND state IN ('SCHEDULED', 'READY')
                  """,
                  reason,
                  Timestamp.from(now),
                  tenantId,
                  notice.noticeId());
        } else if (notice.state().equals("SCHEDULED") && !now.isBefore(notice.scheduledAt())) {
          changed +=
              jdbc.update(
                  """
                  UPDATE agendamento.billing_annual_renewal_notice
                  SET state = 'READY', updated_at = ?
                  WHERE tenant_id = ? AND notice_id = ? AND state = 'SCHEDULED'
                  """,
                  Timestamp.from(now),
                  tenantId,
                  notice.noticeId());
        }
      }
      if (subscription == null) continue;
      Instant scheduledAt = subscription.paidThroughAt().minus(Duration.ofHours(168));
      String state;
      String terminalReason = null;
      if (!now.isBefore(subscription.paidThroughAt())) {
        state = "OBSOLETE";
        terminalReason = "PAID_PERIOD_EXPIRED";
      } else if (!subscription.status().equals("ACTIVE")) {
        continue;
      } else {
        state = now.isBefore(scheduledAt) ? "SCHEDULED" : "READY";
      }
      UUID noticeId =
          UUID.nameUUIDFromBytes(
              (subscription.subscriptionId()
                      + ":annual-renewal-notice:v1:"
                      + subscription.paidThroughAt())
                  .getBytes(StandardCharsets.UTF_8));
      changed +=
          jdbc.update(
              """
              INSERT INTO agendamento.billing_annual_renewal_notice
                (notice_id, tenant_id, subscription_id, subscription_version, paid_through_at,
                 scheduled_at, state, obsolete_reason, created_at, updated_at)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
              ON CONFLICT (subscription_id, paid_through_at) DO NOTHING
              """,
              noticeId,
              tenantId,
              subscription.subscriptionId(),
              subscription.version(),
              Timestamp.from(subscription.paidThroughAt()),
              Timestamp.from(scheduledAt),
              state,
              terminalReason,
              Timestamp.from(now),
              Timestamp.from(now));
    }
    return changed;
  }

  @Override
  @Transactional(readOnly = true)
  public List<SubscriptionDunningRepository.ProviderReference> pendingPaymentEvidence(int limit) {
    if (limit < 1 || limit > 500) {
      throw new IllegalArgumentException("Limite de reconciliação inválido");
    }
    return jdbc.query(
        """
        SELECT DISTINCT inbox.provider_code, inbox.provider_account_id, inbox.provider_reference
        FROM agendamento.billing_event_inbox inbox
        JOIN agendamento.billing_dunning_attempt attempt
          ON attempt.provider_code = inbox.provider_code
         AND attempt.provider_account_id = inbox.provider_account_id
         AND attempt.provider_reference = inbox.provider_reference
        WHERE inbox.tenant_id IS NULL AND inbox.operation_id IS NULL
          AND inbox.dunning_attempt_id IS NULL
          AND attempt.state IN ('ACCEPTED', 'OUTCOME_UNKNOWN', 'RECONCILING',
                                'CONFIRMED', 'DECLINED', 'FAILED_FINAL')
        ORDER BY inbox.provider_code, inbox.provider_account_id, inbox.provider_reference
        LIMIT ?
        """,
        (row, index) ->
            new SubscriptionDunningRepository.ProviderReference(
                row.getString("provider_code"),
                row.getString("provider_account_id"),
                row.getString("provider_reference")),
        limit);
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public int reconcilePaymentEvidence(String providerCode, String accountId, String reference) {
    if (providerCode == null || accountId == null || reference == null) return 0;
    lockReference(providerCode, accountId, reference);
    List<UUID> candidateTenants =
        jdbc.query(
            """
            SELECT DISTINCT tenant_id FROM agendamento.billing_dunning_attempt
            WHERE provider_code = ? AND provider_account_id = ? AND provider_reference = ?
            """,
            (row, index) -> row.getObject("tenant_id", UUID.class),
            providerCode,
            accountId,
            reference);
    if (candidateTenants.size() != 1) return 0;
    UUID tenantId = candidateTenants.getFirst();
    agendaLock.acquire(tenantId);

    Boolean checkoutCollision =
        jdbc.queryForObject(
            """
            SELECT EXISTS (
              SELECT 1 FROM agendamento.billing_checkout_operation
              WHERE provider_code = ? AND provider_account_id = ? AND provider_reference = ?
            )
            """,
            Boolean.class,
            providerCode,
            accountId,
            reference);
    if (Boolean.TRUE.equals(checkoutCollision)) return 0;

    List<PaymentAttemptRow> attempts =
        jdbc.query(
            """
            SELECT attempt.attempt_id, attempt.cycle_id, cycle.subscription_id,
                   attempt.last_provider_sequence, attempt.state AS attempt_state,
                   cycle.state AS cycle_state, subscription.status AS subscription_status,
                   subscription.version AS subscription_version,
                   subscription.paid_through_at AS current_paid_through,
                   attempt.contract_amount_minor_units AS amount_minor_units,
                   attempt.contract_currency AS currency, subscription.plan_code
            FROM agendamento.billing_dunning_attempt attempt
            JOIN agendamento.billing_dunning_cycle cycle USING (cycle_id, tenant_id)
            JOIN agendamento.subscription subscription USING (subscription_id, tenant_id)
            WHERE attempt.tenant_id = ? AND attempt.provider_code = ?
              AND attempt.provider_account_id = ? AND attempt.provider_reference = ?
              AND attempt.state IN ('ACCEPTED', 'OUTCOME_UNKNOWN', 'RECONCILING',
                                    'CONFIRMED', 'DECLINED', 'FAILED_FINAL')
            FOR UPDATE OF attempt, cycle, subscription
            """,
            (row, index) ->
                new PaymentAttemptRow(
                    row.getObject("attempt_id", UUID.class),
                    row.getObject("cycle_id", UUID.class),
                    row.getObject("subscription_id", UUID.class),
                    row.getLong("last_provider_sequence"),
                    row.getString("attempt_state"),
                    row.getString("cycle_state"),
                    row.getString("subscription_status"),
                    row.getLong("subscription_version"),
                    instant(row, "current_paid_through"),
                    row.getObject("amount_minor_units", Long.class),
                    row.getString("currency"),
                    row.getString("plan_code")),
            tenantId,
            providerCode,
            accountId,
            reference);
    if (attempts.size() != 1) return 0;
    PaymentAttemptRow attempt = attempts.getFirst();
    List<InboxPaymentEvent> events =
        jdbc.query(
            """
            SELECT provider_event_id, provider_sequence, outcome, amount_minor_units,
                   currency, occurred_at, paid_through_at
            FROM agendamento.billing_event_inbox
            WHERE provider_code = ? AND provider_account_id = ? AND provider_reference = ?
              AND tenant_id IS NULL AND operation_id IS NULL AND dunning_attempt_id IS NULL
            ORDER BY provider_sequence, provider_event_id
            FOR UPDATE
            """,
            (row, index) ->
                new InboxPaymentEvent(
                    row.getString("provider_event_id"),
                    row.getLong("provider_sequence"),
                    row.getString("outcome"),
                    row.getLong("amount_minor_units"),
                    row.getString("currency"),
                    row.getTimestamp("occurred_at").toInstant(),
                    instant(row, "paid_through_at")),
            providerCode,
            accountId,
            reference);
    int applied = 0;
    for (InboxPaymentEvent event : events) {
      jdbc.update(
          """
          UPDATE agendamento.billing_event_inbox
          SET tenant_id = ?, dunning_attempt_id = ?, disposition = 'PENDING'
          WHERE provider_code = ? AND provider_account_id = ? AND provider_event_id = ?
            AND tenant_id IS NULL AND dunning_attempt_id IS NULL
          """,
          tenantId,
          attempt.attemptId(),
          providerCode,
          accountId,
          event.eventId());
      applyDunningPaymentEvidence(tenantId, providerCode, accountId, event, attempt);
      attempt = paymentAttemptForUpdate(tenantId, attempt.attemptId());
      applied++;
    }
    return applied;
  }

  private void applyDunningPaymentEvidence(
      UUID tenantId,
      String providerCode,
      String accountId,
      InboxPaymentEvent event,
      PaymentAttemptRow attempt) {
    if (event.sequence() <= attempt.lastProviderSequence()) {
      setInboxDisposition(providerCode, accountId, event.eventId(), "OLDER");
      return;
    }
    if (attempt.amountMinorUnits() == null
        || attempt.currency() == null
        || event.amountMinorUnits() != attempt.amountMinorUnits()
        || !event.currency().equals(attempt.currency())
        || (event.outcome().equals("CONFIRMED") && event.paidThroughAt() == null)) {
      setInboxDisposition(providerCode, accountId, event.eventId(), "MISMATCHED");
      return;
    }
    jdbc.update(
        """
        UPDATE agendamento.billing_dunning_attempt
        SET last_provider_sequence = ?, updated_at = ?
        WHERE tenant_id = ? AND attempt_id = ?
        """,
        event.sequence(),
        Timestamp.from(clock.instant()),
        tenantId,
        attempt.attemptId());
    if (event.outcome().equals("PENDING")) {
      setInboxDisposition(providerCode, accountId, event.eventId(), "PENDING");
      return;
    }
    if (event.outcome().equals("DECLINED")) {
      jdbc.update(
          """
          UPDATE agendamento.billing_dunning_attempt
          SET state = 'DECLINED', claim_token = NULL, lease_until = NULL,
              last_error_code = 'PROVIDER_DECLINED', updated_at = ?
          WHERE tenant_id = ? AND attempt_id = ? AND state <> 'CONFIRMED'
          """,
          Timestamp.from(clock.instant()),
          tenantId,
          attempt.attemptId());
      setInboxDisposition(providerCode, accountId, event.eventId(), "DECLINED");
      return;
    }

    Instant now = clock.instant();
    if (attempt.currentPaidThrough() != null
        && !event.paidThroughAt().isAfter(attempt.currentPaidThrough())) {
      if ("ACTIVE".equals(attempt.subscriptionStatus())) {
        settleSupersededAttempt(tenantId, attempt, event.occurredAt());
      } else {
        jdbc.update(
            "UPDATE agendamento.billing_dunning_attempt SET state = 'OUTCOME_UNKNOWN', last_error_code = 'PAYMENT_PERIOD_NOT_NEWER', updated_at = ? WHERE tenant_id = ? AND attempt_id = ?",
            Timestamp.from(now),
            tenantId,
            attempt.attemptId());
      }
      setInboxDisposition(providerCode, accountId, event.eventId(), "OLDER");
      return;
    }
    if (!event.paidThroughAt().isAfter(now)) {
      // The provider confirms payment, but its authoritative period is already over.
      // Settle only this cycle and preserve the evidence without granting expired access.
      jdbc.update(
          """
          UPDATE agendamento.billing_dunning_cycle
          SET state = 'PAID', closed_at = ?
          WHERE tenant_id = ? AND cycle_id = ? AND state IN ('OPEN', 'SUSPENDED')
          """,
          Timestamp.from(event.occurredAt()),
          tenantId,
          attempt.cycleId());
      jdbc.update(
          """
          UPDATE agendamento.billing_dunning_attempt
          SET state = 'CANCELLED', claim_token = NULL, lease_until = NULL,
              last_error_code = 'PAYMENT_CONFIRMED', updated_at = ?
          WHERE tenant_id = ? AND cycle_id = ? AND attempt_id <> ?
            AND state IN ('SCHEDULED', 'BLOCKED_CONFIGURATION', 'CLAIMED', 'RETRYABLE_NO_EFFECT')
          """,
          Timestamp.from(now),
          tenantId,
          attempt.cycleId(),
          attempt.attemptId());
      jdbc.update(
          """
          UPDATE agendamento.billing_dunning_attempt
          SET state = 'CONFIRMED', claim_token = NULL, lease_until = NULL,
              last_error_code = 'CONFIRMED_PERIOD_EXPIRED', updated_at = ?
          WHERE tenant_id = ? AND attempt_id = ?
          """,
          Timestamp.from(now),
          tenantId,
          attempt.attemptId());
      setInboxDisposition(providerCode, accountId, event.eventId(), "EXPIRED_PERIOD");
      return;
    }
    if ("ACTIVE".equals(attempt.subscriptionStatus())) {
      extendActivePeriod(tenantId, providerCode, accountId, event, attempt, now);
      return;
    }
    if (!List.of("PAST_DUE", "SUSPENDED").contains(attempt.subscriptionStatus())
        || !List.of("OPEN", "SUSPENDED").contains(attempt.cycleState())) {
      setInboxDisposition(providerCode, accountId, event.eventId(), "OLDER");
      return;
    }

    int changed =
        jdbc.update(
            """
            UPDATE agendamento.subscription
            SET status = 'ACTIVE', paid_through_at = ?, grace_ends_at = NULL,
                version = version + 1
            WHERE tenant_id = ? AND subscription_id = ? AND version = ?
              AND status IN ('PAST_DUE', 'SUSPENDED')
              AND paid_through_at < ?
            """,
            Timestamp.from(event.paidThroughAt()),
            tenantId,
            attempt.subscriptionId(),
            attempt.subscriptionVersion(),
            Timestamp.from(event.paidThroughAt()));
    if (changed != 1) throw new IllegalStateException("CAS da renovação foi perdido");
    long after = attempt.subscriptionVersion() + 1;
    UUID operationId = attempt.attemptId();
    jdbc.update(
        """
        INSERT INTO agendamento.subscription_history
          (event_id, tenant_id, subscription_id, event_type, from_status, to_status,
           event_at, version, operation_id)
        VALUES (?, ?, ?, 'PAYMENT_CONFIRMED', ?, 'ACTIVE', ?, ?, ?)
        """,
        UUID.randomUUID(),
        tenantId,
        attempt.subscriptionId(),
        attempt.subscriptionStatus(),
        Timestamp.from(event.occurredAt()),
        after,
        operationId);
    closeCyclesForPayment(tenantId, attempt.subscriptionId(), event.occurredAt());
    jdbc.update(
        """
        UPDATE agendamento.billing_dunning_attempt
        SET state = 'CONFIRMED', claim_token = NULL, lease_until = NULL,
            last_error_code = NULL, updated_at = ?
        WHERE tenant_id = ? AND attempt_id = ?
        """,
        Timestamp.from(now),
        tenantId,
        attempt.attemptId());
    recordEffects(
        tenantId,
        attempt.subscriptionId(),
        operationId,
        "SUBSCRIPTION_PAYMENT_CONFIRMED",
        "SubscriptionReactivated",
        attempt.subscriptionVersion(),
        after);
    setInboxDisposition(providerCode, accountId, event.eventId(), "APPLIED");
  }

  private void extendActivePeriod(
      UUID tenantId,
      String providerCode,
      String accountId,
      InboxPaymentEvent event,
      PaymentAttemptRow attempt,
      Instant now) {
    int changed =
        jdbc.update(
            """
            UPDATE agendamento.subscription
            SET paid_through_at = ?, version = version + 1
            WHERE tenant_id = ? AND subscription_id = ? AND status = 'ACTIVE'
              AND version = ? AND paid_through_at < ?
            """,
            Timestamp.from(event.paidThroughAt()),
            tenantId,
            attempt.subscriptionId(),
            attempt.subscriptionVersion(),
            Timestamp.from(event.paidThroughAt()));
    if (changed != 1) throw new IllegalStateException("CAS da extensão do período foi perdido");
    long after = attempt.subscriptionVersion() + 1;
    UUID operationId = attempt.attemptId();
    jdbc.update(
        """
        INSERT INTO agendamento.subscription_history
          (event_id, tenant_id, subscription_id, event_type, from_status, to_status,
           event_at, version, operation_id)
        VALUES (?, ?, ?, 'PAYMENT_CONFIRMED', 'ACTIVE', 'ACTIVE', ?, ?, ?)
        """,
        UUID.randomUUID(),
        tenantId,
        attempt.subscriptionId(),
        Timestamp.from(event.occurredAt()),
        after,
        operationId);
    closeCyclesForPayment(tenantId, attempt.subscriptionId(), event.occurredAt());
    jdbc.update(
        """
        UPDATE agendamento.billing_dunning_attempt
        SET state = 'CONFIRMED', claim_token = NULL, lease_until = NULL,
            last_error_code = NULL, updated_at = ?
        WHERE tenant_id = ? AND attempt_id = ?
        """,
        Timestamp.from(now),
        tenantId,
        attempt.attemptId());
    recordEffects(
        tenantId,
        attempt.subscriptionId(),
        operationId,
        "SUBSCRIPTION_PAYMENT_CONFIRMED",
        "SubscriptionPeriodExtended",
        attempt.subscriptionVersion(),
        after);
    setInboxDisposition(providerCode, accountId, event.eventId(), "APPLIED");
  }

  private void settleSupersededAttempt(UUID tenantId, PaymentAttemptRow attempt, Instant paidAt) {
    closeCyclesForPayment(tenantId, attempt.subscriptionId(), paidAt);
    jdbc.update(
        """
        UPDATE agendamento.billing_dunning_attempt
        SET state = 'CANCELLED', claim_token = NULL, lease_until = NULL,
            last_error_code = 'SUPERSEDED_BY_NEWER_PAYMENT', updated_at = ?
        WHERE tenant_id = ? AND attempt_id = ?
        """,
        Timestamp.from(clock.instant()),
        tenantId,
        attempt.attemptId());
  }

  private PaymentAttemptRow paymentAttemptForUpdate(UUID tenantId, UUID attemptId) {
    return jdbc.queryForObject(
        """
        SELECT attempt.attempt_id, attempt.cycle_id, cycle.subscription_id,
               attempt.last_provider_sequence, attempt.state AS attempt_state,
               cycle.state AS cycle_state, subscription.status AS subscription_status,
               subscription.version AS subscription_version,
               subscription.paid_through_at AS current_paid_through,
               attempt.contract_amount_minor_units AS amount_minor_units,
               attempt.contract_currency AS currency, subscription.plan_code
        FROM agendamento.billing_dunning_attempt attempt
        JOIN agendamento.billing_dunning_cycle cycle USING (cycle_id, tenant_id)
        JOIN agendamento.subscription subscription USING (subscription_id, tenant_id)
        WHERE attempt.tenant_id = ? AND attempt.attempt_id = ?
        FOR UPDATE OF attempt, cycle, subscription
        """,
        (row, index) ->
            new PaymentAttemptRow(
                row.getObject("attempt_id", UUID.class),
                row.getObject("cycle_id", UUID.class),
                row.getObject("subscription_id", UUID.class),
                row.getLong("last_provider_sequence"),
                row.getString("attempt_state"),
                row.getString("cycle_state"),
                row.getString("subscription_status"),
                row.getLong("subscription_version"),
                instant(row, "current_paid_through"),
                row.getObject("amount_minor_units", Long.class),
                row.getString("currency"),
                row.getString("plan_code")),
        tenantId,
        attemptId);
  }

  private void setInboxDisposition(
      String providerCode, String accountId, String eventId, String disposition) {
    jdbc.update(
        """
        UPDATE agendamento.billing_event_inbox
        SET disposition = ?
        WHERE provider_code = ? AND provider_account_id = ? AND provider_event_id = ?
        """,
        disposition,
        providerCode,
        accountId,
        eventId);
  }

  @Override
  @Transactional(readOnly = true)
  public DunningMetricsSnapshot metricsSnapshot(Instant asOf) {
    jdbc.execute("SET LOCAL statement_timeout = '2s'");
    return jdbc.queryForObject(
        """
        WITH reference_time AS (SELECT ?::timestamptz AS as_of),
        backlog_sample AS (
          SELECT c.state AS cycle_state, a.state AS attempt_state,
                 CASE WHEN a.state = 'RETRYABLE_NO_EFFECT' THEN a.next_attempt_at
                      WHEN a.state IN ('CLAIMED', 'SUBMITTING', 'RECONCILING') THEN a.lease_until
                      ELSE a.scheduled_at END AS due_at,
                 reference_time.as_of
          FROM agendamento.billing_dunning_attempt a
          JOIN agendamento.billing_dunning_cycle c USING (cycle_id, tenant_id)
          JOIN agendamento.subscription s USING (subscription_id, tenant_id)
          CROSS JOIN reference_time
          WHERE c.state = 'OPEN' AND s.status = 'PAST_DUE'
            AND s.grace_ends_at > reference_time.as_of
            AND (
              (a.state IN ('SCHEDULED', 'BLOCKED_CONFIGURATION')
                AND a.scheduled_at <= reference_time.as_of)
              OR (a.state = 'RETRYABLE_NO_EFFECT'
                AND a.next_attempt_at <= reference_time.as_of)
              OR (a.state IN ('CLAIMED', 'SUBMITTING', 'RECONCILING')
                AND a.lease_until <= reference_time.as_of)
            )
          ORDER BY CASE WHEN a.ever_started THEN a.next_attempt_at ELSE a.scheduled_at END,
                   a.attempt_id
          LIMIT 10001
        ),
        recent_attempts AS (
          SELECT a.state AS attempt_state, a.technical_attempts
          FROM agendamento.billing_dunning_attempt a
          ORDER BY a.updated_at DESC, a.attempt_id DESC
          LIMIT 10001
        ),
        pending_outcomes AS (
          SELECT a.attempt_id
          FROM agendamento.billing_dunning_attempt a
          WHERE a.state IN ('OUTCOME_UNKNOWN', 'RECONCILING')
          ORDER BY a.updated_at DESC, a.attempt_id DESC
          LIMIT 10001
        )
        SELECT
          LEAST(count(*), 10000) AS backlog,
          LEAST(count(*) FILTER (WHERE attempt_state IN
            ('SCHEDULED', 'BLOCKED_CONFIGURATION', 'RETRYABLE_NO_EFFECT')
            AND due_at <= backlog_sample.as_of), 10000) AS overdue,
          COALESCE(
            MAX(EXTRACT(EPOCH FROM (backlog_sample.as_of - due_at))) FILTER (
              WHERE attempt_state IN ('SCHEDULED', 'BLOCKED_CONFIGURATION', 'RETRYABLE_NO_EFFECT')
                AND due_at <= backlog_sample.as_of),
            0)::bigint AS oldest_overdue_seconds,
          LEAST((SELECT count(*) FROM pending_outcomes), 10000) AS outcome_unknown,
          LEAST((SELECT count(*) FROM recent_attempts WHERE attempt_state = 'ACCEPTED'), 10000) AS accepted_transport,
          LEAST((SELECT count(*) FROM recent_attempts WHERE attempt_state = 'DECLINED'), 10000) AS declined,
          LEAST((SELECT count(*) FROM recent_attempts WHERE attempt_state = 'FAILED_FINAL'), 10000) AS failed_final,
          LEAST((SELECT count(*) FROM recent_attempts WHERE attempt_state = 'SKIPPED_LATE_RECOVERY'), 10000) AS skipped_late_recovery,
          LEAST(COALESCE((SELECT sum(technical_attempts) FROM recent_attempts), 0), 10000) AS technical_attempts,
          ((SELECT count(*) FROM backlog_sample) > 10000
            OR (SELECT count(*) FROM recent_attempts) > 10000
            OR (SELECT count(*) FROM pending_outcomes) > 10000) AS capped
        FROM backlog_sample CROSS JOIN reference_time
        """,
        (row, index) ->
            new DunningMetricsSnapshot(
                row.getLong("backlog"),
                row.getLong("overdue"),
                row.getLong("oldest_overdue_seconds"),
                row.getLong("outcome_unknown"),
                row.getLong("accepted_transport"),
                row.getLong("declined"),
                row.getLong("failed_final"),
                row.getLong("skipped_late_recovery"),
                row.getLong("technical_attempts"),
                row.getBoolean("capped")),
        Timestamp.from(asOf));
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Optional<DunningAttemptClaim> claimNextAttempt(
      Duration lease, boolean reconcilerAvailable) {
    if (lease == null || lease.isZero() || lease.isNegative()) {
      throw new IllegalArgumentException("Lease inválida");
    }
    Instant firstNow = clock.instant();
    List<UUID> tenants =
        jdbc.query(
            """
            SELECT DISTINCT c.tenant_id
            FROM agendamento.billing_dunning_cycle c
            JOIN agendamento.subscription s
              ON s.subscription_id = c.subscription_id AND s.tenant_id = c.tenant_id
            JOIN agendamento.billing_dunning_attempt a
              ON a.cycle_id = c.cycle_id AND a.tenant_id = c.tenant_id
            WHERE (a.state IN ('CLAIMED', 'SUBMITTING', 'RECONCILING')
                     AND a.lease_until <= ?)
               OR (a.state = 'OUTCOME_UNKNOWN' AND ?
                   AND (a.next_attempt_at IS NULL OR a.next_attempt_at <= ?))
               OR (c.state = 'OPEN' AND s.status = 'PAST_DUE'
                     AND s.grace_ends_at > ?
                     AND ((a.state IN ('SCHEDULED', 'BLOCKED_CONFIGURATION')
                             AND a.scheduled_at <= ?)
                          OR (a.state = 'RETRYABLE_NO_EFFECT'
                             AND a.next_attempt_at <= ?)))
            ORDER BY c.tenant_id
            LIMIT 32
            """,
            (row, index) -> row.getObject(1, UUID.class),
            Timestamp.from(firstNow),
            reconcilerAvailable,
            Timestamp.from(firstNow),
            Timestamp.from(firstNow),
            Timestamp.from(firstNow),
            Timestamp.from(firstNow));

    for (UUID tenantId : tenants) {
      agendaLock.acquire(tenantId);
      Instant now = clock.instant();
      recoverExpiredClaims(tenantId, now);

      if (reconcilerAvailable) {
        Optional<DunningAttemptClaim> unknown = claimUnknown(tenantId, lease, now);
        if (unknown.isPresent()) return unknown;
      }

      Optional<DunningAttemptClaim> active = claimDueForTenant(tenantId, lease, now);
      if (active.isPresent()) return active;
    }
    return Optional.empty();
  }

  private void recoverExpiredClaims(UUID tenantId, Instant now) {
    jdbc.update(
        """
        UPDATE agendamento.billing_dunning_attempt
        SET state = CASE WHEN ever_started THEN 'RETRYABLE_NO_EFFECT' ELSE 'SCHEDULED' END,
            next_attempt_at = CASE WHEN ever_started THEN COALESCE(next_attempt_at, ?) ELSE NULL END,
            claim_token = NULL, lease_until = NULL, updated_at = ?,
            last_error_code = 'LEASE_EXPIRED_BEFORE_SEND'
        WHERE tenant_id = ? AND state = 'CLAIMED' AND lease_until <= ?
        """,
        Timestamp.from(now),
        Timestamp.from(now),
        tenantId,
        Timestamp.from(now));
    jdbc.update(
        """
        UPDATE agendamento.billing_dunning_attempt
        SET state = 'OUTCOME_UNKNOWN', claim_token = NULL, lease_until = NULL,
            updated_at = ?, last_error_code = 'LEASE_EXPIRED_AFTER_SEND_START'
        WHERE tenant_id = ? AND state = 'SUBMITTING' AND lease_until <= ?
        """,
        Timestamp.from(now),
        tenantId,
        Timestamp.from(now));
    jdbc.update(
        """
        UPDATE agendamento.billing_dunning_attempt
        SET state = 'OUTCOME_UNKNOWN', claim_token = NULL, lease_until = NULL,
            updated_at = ?, last_error_code = 'LEASE_EXPIRED_DURING_RECONCILIATION'
        WHERE tenant_id = ? AND state = 'RECONCILING' AND lease_until <= ?
        """,
        Timestamp.from(now),
        tenantId,
        Timestamp.from(now));
  }

  private Optional<DunningAttemptClaim> claimUnknown(UUID tenantId, Duration lease, Instant now) {
    List<AttemptRow> rows =
        jdbc.query(
            """
            SELECT a.attempt_id, a.external_idempotency_key, a.cycle_id, c.subscription_id,
                   a.logical_sequence, a.technical_attempts, a.scheduled_at,
                   a.provider_code, a.provider_account_id, a.provider_reference, a.contract_plan_code AS plan_code,
                   s.paid_through_at AS period_ends_at,
                   a.contract_amount_minor_units AS amount_minor_units,
                   a.contract_currency AS currency,
                   a.contract_terms_version AS terms_version,
                   a.contract_offer_version AS offer_version
            FROM agendamento.billing_dunning_attempt a
            JOIN agendamento.billing_dunning_cycle c USING (cycle_id, tenant_id)
            JOIN agendamento.subscription s USING (subscription_id, tenant_id)
            WHERE a.tenant_id = ? AND a.state = 'OUTCOME_UNKNOWN'
              AND (a.next_attempt_at IS NULL OR a.next_attempt_at <= ?)
            ORDER BY a.updated_at, a.attempt_id
            FOR UPDATE OF a SKIP LOCKED
            LIMIT 1
            """,
            (row, index) -> mapAttemptRow(row),
            tenantId,
            Timestamp.from(now));
    if (rows.isEmpty()) return Optional.empty();
    return Optional.of(claim(rows.getFirst(), tenantId, "RECONCILE", lease, now));
  }

  private Optional<DunningAttemptClaim> claimDueForTenant(
      UUID tenantId, Duration lease, Instant now) {
    List<CycleRow> cycles =
        jdbc.query(
            """
            SELECT c.cycle_id, c.subscription_id, c.grace_ends_at, s.plan_code
            FROM agendamento.billing_dunning_cycle c
            JOIN agendamento.subscription s USING (subscription_id, tenant_id)
            WHERE c.tenant_id = ? AND c.state = 'OPEN' AND s.status = 'PAST_DUE'
              AND s.grace_ends_at > ?
              AND NOT EXISTS (
                SELECT 1 FROM agendamento.billing_checkout_operation checkout
                WHERE checkout.tenant_id = c.tenant_id
                  AND checkout.state IN ('REQUESTED', 'SUBMITTING', 'CHECKOUT_READY', 'OUTCOME_UNKNOWN')
              )
              AND NOT EXISTS (
                SELECT 1
                FROM agendamento.billing_dunning_cycle unresolved_cycle
                JOIN agendamento.billing_dunning_attempt unresolved_attempt
                  USING (cycle_id, tenant_id)
                WHERE unresolved_cycle.tenant_id = c.tenant_id
                  AND unresolved_cycle.subscription_id = c.subscription_id
                  AND unresolved_attempt.state IN (
                    'CLAIMED', 'SUBMITTING', 'OUTCOME_UNKNOWN', 'RECONCILING', 'ACCEPTED')
              )
            ORDER BY c.due_at DESC
            LIMIT 4
            """,
            (row, index) ->
                new CycleRow(
                    row.getObject("cycle_id", UUID.class),
                    row.getObject("subscription_id", UUID.class),
                    row.getTimestamp("grace_ends_at").toInstant(),
                    row.getString("plan_code")),
            tenantId,
            Timestamp.from(now));

    for (CycleRow cycle : cycles) {
      List<String> blockers =
          jdbc.query(
              """
              SELECT state FROM agendamento.billing_dunning_attempt
              WHERE tenant_id = ? AND cycle_id = ?
                AND state IN ('CLAIMED', 'SUBMITTING', 'RETRYABLE_NO_EFFECT',
                              'OUTCOME_UNKNOWN', 'RECONCILING', 'ACCEPTED')
              ORDER BY logical_sequence LIMIT 1
              """,
              (row, index) -> row.getString(1),
              tenantId,
              cycle.cycleId());
      if (!blockers.isEmpty()) {
        String state = blockers.getFirst();
        if (!state.equals("RETRYABLE_NO_EFFECT")) continue;
        List<AttemptRow> retries =
            jdbc.query(
                """
                SELECT a.attempt_id, a.external_idempotency_key, a.cycle_id, c.subscription_id,
                       a.logical_sequence, a.technical_attempts, a.scheduled_at,
                       a.provider_code, a.provider_account_id, a.provider_reference, a.contract_plan_code AS plan_code,
                       s.paid_through_at AS period_ends_at,
                       a.contract_amount_minor_units AS amount_minor_units,
                       a.contract_currency AS currency,
                       a.contract_terms_version AS terms_version,
                       a.contract_offer_version AS offer_version
                FROM agendamento.billing_dunning_attempt a
                JOIN agendamento.billing_dunning_cycle c USING (cycle_id, tenant_id)
                JOIN agendamento.subscription s USING (subscription_id, tenant_id)
                WHERE a.tenant_id = ? AND a.cycle_id = ?
                  AND a.state = 'RETRYABLE_NO_EFFECT' AND a.next_attempt_at <= ?
                ORDER BY a.logical_sequence LIMIT 1
                FOR UPDATE OF a SKIP LOCKED
                """,
                (row, index) -> mapAttemptRow(row),
                tenantId,
                cycle.cycleId(),
                Timestamp.from(now));
        if (retries.isEmpty()) return Optional.empty();
        return Optional.of(claim(retries.getFirst(), tenantId, "SEND", lease, now));
      }

      List<AttemptRow> due =
          jdbc.query(
              """
          SELECT a.attempt_id, a.external_idempotency_key, a.cycle_id, c.subscription_id,
                 a.logical_sequence, a.technical_attempts, a.scheduled_at,
                 a.provider_code, a.provider_account_id, a.provider_reference, a.contract_plan_code AS plan_code,
                 s.paid_through_at AS period_ends_at,
                 a.contract_amount_minor_units AS amount_minor_units,
                 a.contract_currency AS currency,
                 a.contract_terms_version AS terms_version,
                 a.contract_offer_version AS offer_version
              FROM agendamento.billing_dunning_attempt a
              JOIN agendamento.billing_dunning_cycle c USING (cycle_id, tenant_id)
              JOIN agendamento.subscription s USING (subscription_id, tenant_id)
              WHERE a.tenant_id = ? AND a.cycle_id = ? AND a.ever_started = FALSE
                AND a.state IN ('SCHEDULED', 'BLOCKED_CONFIGURATION')
                AND a.scheduled_at <= ?
              ORDER BY a.logical_sequence DESC
              FOR UPDATE OF a SKIP LOCKED
              LIMIT 1
              """,
              (row, index) -> mapAttemptRow(row),
              tenantId,
              cycle.cycleId(),
              Timestamp.from(now));
      if (due.isEmpty()) continue;
      AttemptRow latest = due.getFirst();
      jdbc.update(
          """
          UPDATE agendamento.billing_dunning_attempt
          SET state = 'SKIPPED_LATE_RECOVERY', skip_reason = 'LATE_RECOVERY_OLDER_DUE',
              last_error_code = 'LATE_RECOVERY_SKIPPED', updated_at = ?
          WHERE tenant_id = ? AND cycle_id = ? AND ever_started = FALSE
            AND state IN ('SCHEDULED', 'BLOCKED_CONFIGURATION')
            AND scheduled_at <= ? AND logical_sequence < ?
          """,
          Timestamp.from(now),
          tenantId,
          cycle.cycleId(),
          Timestamp.from(now),
          latest.logicalSequence());
      return Optional.of(claim(latest, tenantId, "SEND", lease, now));
    }
    return Optional.empty();
  }

  private DunningAttemptClaim claim(
      AttemptRow row, UUID tenantId, String workType, Duration lease, Instant now) {
    UUID token = UUID.randomUUID();
    int changed;
    if (workType.equals("RECONCILE")) {
      changed =
          jdbc.update(
              """
              UPDATE agendamento.billing_dunning_attempt
              SET state = 'RECONCILING', claim_token = ?, lease_until = ?, updated_at = ?
              WHERE tenant_id = ? AND attempt_id = ? AND state = 'OUTCOME_UNKNOWN'
              """,
              token,
              Timestamp.from(now.plus(lease)),
              Timestamp.from(now),
              tenantId,
              row.attemptId());
    } else {
      changed =
          jdbc.update(
              """
              UPDATE agendamento.billing_dunning_attempt
              SET state = 'CLAIMED', claim_token = ?, lease_until = ?, updated_at = ?
              WHERE tenant_id = ? AND attempt_id = ?
                AND state IN ('SCHEDULED', 'BLOCKED_CONFIGURATION', 'RETRYABLE_NO_EFFECT')
                AND (CASE WHEN ever_started THEN next_attempt_at ELSE scheduled_at END) <= ?
              """,
              token,
              Timestamp.from(now.plus(lease)),
              Timestamp.from(now),
              tenantId,
              row.attemptId(),
              Timestamp.from(now));
    }
    if (changed != 1) return null;
    return new DunningAttemptClaim(
        tenantId,
        row.subscriptionId(),
        row.cycleId(),
        row.attemptId(),
        row.externalIdempotencyKey(),
        token,
        row.logicalSequence(),
        row.technicalAttempts(),
        workType,
        row.planCode(),
        row.scheduledAt(),
        now.plus(lease),
        row.providerCode(),
        row.providerAccountId(),
        row.providerReference(),
        row.periodEndsAt(),
        row.amountMinorUnits(),
        row.currency(),
        row.termsVersion(),
        row.offerVersion());
  }

  private AttemptRow mapAttemptRow(ResultSet row) throws SQLException {
    return new AttemptRow(
        row.getObject("attempt_id", UUID.class),
        row.getObject("external_idempotency_key", UUID.class),
        row.getObject("cycle_id", UUID.class),
        row.getObject("subscription_id", UUID.class),
        row.getInt("logical_sequence"),
        row.getInt("technical_attempts"),
        row.getTimestamp("scheduled_at").toInstant(),
        row.getString("provider_code"),
        row.getString("provider_account_id"),
        row.getString("provider_reference"),
        row.getString("plan_code"),
        instant(row, "period_ends_at"),
        row.getObject("amount_minor_units", Long.class),
        row.getString("currency"),
        row.getString("terms_version"),
        row.getString("offer_version"));
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public boolean authorizeAttemptSend(DunningAttemptClaim claim) {
    agendaLock.acquire(claim.tenantId());
    Instant now = clock.instant();
    List<SendAuthorizationRow> rows =
        jdbc.query(
            """
            SELECT a.state, a.claim_token, a.lease_until, a.ever_started,
                   a.scheduled_at, a.next_attempt_at, a.send_claim_token,
                   c.state AS cycle_state, s.status AS subscription_status,
                   a.contract_plan_code AS plan_code, s.grace_ends_at, s.paid_through_at AS period_ends_at,
                   a.contract_amount_minor_units AS amount_minor_units,
                   a.contract_currency AS currency,
                   a.contract_terms_version AS terms_version,
                   a.contract_offer_version AS offer_version
            FROM agendamento.billing_dunning_attempt a
            JOIN agendamento.billing_dunning_cycle c USING (cycle_id, tenant_id)
            JOIN agendamento.subscription s USING (subscription_id, tenant_id)
            WHERE a.tenant_id = ? AND a.attempt_id = ? AND a.cycle_id = ?
              AND s.subscription_id = ?
            FOR UPDATE OF a, c, s
            """,
            (row, index) ->
                new SendAuthorizationRow(
                    row.getString("state"),
                    row.getObject("claim_token", UUID.class),
                    instant(row, "lease_until"),
                    row.getBoolean("ever_started"),
                    row.getTimestamp("scheduled_at").toInstant(),
                    instant(row, "next_attempt_at"),
                    row.getString("cycle_state"),
                    row.getString("subscription_status"),
                    row.getString("plan_code"),
                    instant(row, "grace_ends_at"),
                    instant(row, "period_ends_at"),
                    row.getObject("amount_minor_units", Long.class),
                    row.getString("currency"),
                    row.getString("terms_version"),
                    row.getString("offer_version")),
            claim.tenantId(),
            claim.attemptId(),
            claim.cycleId(),
            claim.subscriptionId());
    if (rows.isEmpty()) return false;
    SendAuthorizationRow row = rows.getFirst();
    if (!"CLAIMED".equals(row.state())
        || !claim.claimToken().equals(row.claimToken())
        || row.leaseUntil() == null
        || !row.leaseUntil().isAfter(now)) return false;
    boolean due =
        row.everStarted()
            ? row.nextAttemptAt() != null && !now.isBefore(row.nextAttemptAt())
            : !now.isBefore(row.scheduledAt());
    boolean eligible =
        due
            && "OPEN".equals(row.cycleState())
            && "PAST_DUE".equals(row.subscriptionStatus())
            && row.graceEndsAt() != null
            && now.isBefore(row.graceEndsAt());
    if (!eligible) {
      jdbc.update(
          """
          UPDATE agendamento.billing_dunning_attempt
          SET state = 'CANCELLED', claim_token = NULL, lease_until = NULL,
              last_error_code = 'DUNNING_CYCLE_NOT_ELIGIBLE', updated_at = ?
          WHERE tenant_id = ? AND attempt_id = ? AND state = 'CLAIMED' AND claim_token = ?
          """,
          Timestamp.from(now),
          claim.tenantId(),
          claim.attemptId(),
          claim.claimToken());
      return false;
    }
    if (row.amountMinorUnits() == null
        || row.currency() == null
        || row.termsVersion() == null
        || row.offerVersion() == null
        || row.planCode() == null
        || !Objects.equals(claim.planCode(), row.planCode())
        || !Objects.equals(claim.periodEndsAt(), row.periodEndsAt())
        || !Objects.equals(claim.amountMinorUnits(), row.amountMinorUnits())
        || !Objects.equals(claim.currency(), row.currency())
        || !Objects.equals(claim.termsVersion(), row.termsVersion())
        || !Objects.equals(claim.offerVersion(), row.offerVersion())) {
      blockClaimLocked(claim, now, "CONTRACT_TERMS_UNAVAILABLE_OR_CHANGED");
      return false;
    }
    if (hasOpenCheckout(claim.tenantId())) {
      blockClaimLocked(claim, now, "CHECKOUT_OPERATION_IN_PROGRESS");
      return false;
    }
    boolean authorized;
    try {
      authorized =
          switch (row.planCode()) {
            case "MONTHLY" ->
                chargeAuthorization.isAuthorized(
                    claim.tenantId(), claim.subscriptionId(), row.planCode(), now);
            case "ANNUAL" ->
                annualAuthorization.isAuthorizedForPeriod(
                    claim.tenantId(), claim.subscriptionId(), row.periodEndsAt(), now);
            default -> false;
          };
    } catch (RuntimeException unavailable) {
      authorized = false;
    }
    now = clock.instant();
    due =
        row.everStarted()
            ? row.nextAttemptAt() != null && !now.isBefore(row.nextAttemptAt())
            : !now.isBefore(row.scheduledAt());
    eligible =
        due
            && "OPEN".equals(row.cycleState())
            && "PAST_DUE".equals(row.subscriptionStatus())
            && row.graceEndsAt() != null
            && now.isBefore(row.graceEndsAt());
    if (!eligible) {
      jdbc.update(
          """
          UPDATE agendamento.billing_dunning_attempt
          SET state = 'CANCELLED', claim_token = NULL, lease_until = NULL,
              last_error_code = 'DUNNING_CYCLE_NOT_ELIGIBLE', updated_at = ?
          WHERE tenant_id = ? AND attempt_id = ? AND state = 'CLAIMED' AND claim_token = ?
          """,
          Timestamp.from(now),
          claim.tenantId(),
          claim.attemptId(),
          claim.claimToken());
      return false;
    }
    if (!authorized) {
      blockClaimLocked(claim, now, "RECURRING_AUTHORIZATION_UNAVAILABLE");
      return false;
    }
    return jdbc.update(
            """
            UPDATE agendamento.billing_dunning_attempt
            SET state = 'SUBMITTING', ever_started = TRUE,
                technical_attempts = technical_attempts + 1,
                send_claim_token = claim_token, next_attempt_at = NULL,
                last_error_code = NULL, updated_at = ?
            WHERE tenant_id = ? AND attempt_id = ? AND state = 'CLAIMED'
              AND claim_token = ? AND lease_until > ?
            """,
            Timestamp.from(now),
            claim.tenantId(),
            claim.attemptId(),
            claim.claimToken(),
            Timestamp.from(now))
        == 1;
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public boolean blockAttemptForConfiguration(DunningAttemptClaim claim, String reasonCode) {
    Instant now = clock.instant();
    return blockClaimLocked(claim, now, reasonCode);
  }

  private boolean hasOpenCheckout(UUID tenantId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM agendamento.billing_checkout_operation WHERE tenant_id = ? AND state IN ('REQUESTED', 'SUBMITTING', 'CHECKOUT_READY', 'OUTCOME_UNKNOWN'))",
            Boolean.class,
            tenantId));
  }

  private boolean blockClaimLocked(DunningAttemptClaim claim, Instant now, String reasonCode) {
    if (reasonCode == null || !reasonCode.matches("[A-Z0-9_]{1,48}")) {
      throw new IllegalArgumentException("Motivo operacional inválido");
    }
    return jdbc.update(
            """
            UPDATE agendamento.billing_dunning_attempt
            SET state = CASE WHEN ever_started THEN 'RETRYABLE_NO_EFFECT'
                             ELSE 'BLOCKED_CONFIGURATION' END,
                claim_token = NULL, lease_until = NULL,
                next_attempt_at = CASE WHEN ever_started
                                       THEN COALESCE(next_attempt_at, ?) ELSE NULL END,
                last_error_code = ?, updated_at = ?
            WHERE tenant_id = ? AND attempt_id = ? AND state = 'CLAIMED'
              AND claim_token = ?
            """,
            Timestamp.from(now.plusSeconds(30)),
            reasonCode,
            Timestamp.from(now),
            claim.tenantId(),
            claim.attemptId(),
            claim.claimToken())
        == 1;
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public boolean recordTransportResult(
      DunningAttemptClaim claim,
      DunningChargeTransport.TransportResult result,
      Duration retryDelay,
      int maxTechnicalAttempts) {
    return recordAttemptResult(claim, result, retryDelay, maxTechnicalAttempts, false);
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public boolean recordReconciliationResult(
      DunningAttemptClaim claim,
      DunningChargeTransport.TransportResult result,
      Duration retryDelay,
      int maxTechnicalAttempts) {
    return recordAttemptResult(claim, result, retryDelay, maxTechnicalAttempts, true);
  }

  private boolean recordAttemptResult(
      DunningAttemptClaim claim,
      DunningChargeTransport.TransportResult result,
      Duration retryDelay,
      int maxTechnicalAttempts,
      boolean reconciliation) {
    if (retryDelay == null || retryDelay.isNegative() || maxTechnicalAttempts < 1) {
      throw new IllegalArgumentException("Política de retry inválida");
    }
    Instant now = clock.instant();
    List<Integer> numbers =
        jdbc.query(
            "SELECT technical_attempts FROM agendamento.billing_dunning_attempt WHERE tenant_id = ? AND attempt_id = ?",
            (row, index) -> row.getInt(1),
            claim.tenantId(),
            claim.attemptId());
    if (numbers.isEmpty()) return false;
    int technicalAttempts = numbers.getFirst();
    String nextState = stateFor(result.outcome(), technicalAttempts, maxTechnicalAttempts);
    Instant nextAt =
        nextState.equals("RETRYABLE_NO_EFFECT")
            ? now.plus(retryDelay)
            : nextState.equals("OUTCOME_UNKNOWN")
                ? (reconciliation ? now.plus(retryDelay) : now)
                : null;
    String providerCode =
        result.providerCode() == null ? claim.providerCode() : result.providerCode();
    String providerAccount = result.providerAccountId();
    String providerReference = result.providerReference();
    boolean missingReferenceContext =
        providerReference != null && (providerAccount == null || providerCode == null);
    if (missingReferenceContext) {
      providerReference = null;
      if (result.outcome() == DunningChargeTransport.Outcome.ACCEPTED) {
        nextState = "OUTCOME_UNKNOWN";
        nextAt = null;
      }
    }
    if (providerCode != null && providerAccount != null && providerReference != null) {
      lockReference(providerCode, providerAccount, providerReference);
    }
    // Evidence correlation locks reference -> tenant -> attempt. Keep result persistence in
    // the same order so a late transport result cannot deadlock payment settlement.
    agendaLock.acquire(claim.tenantId());
    String errorCode =
        missingReferenceContext && result.outcome() == DunningChargeTransport.Outcome.ACCEPTED
            ? "PROVIDER_REFERENCE_CONTEXT_MISSING"
            : result.safeErrorCode();
    String sql =
        reconciliation
            ? """
              UPDATE agendamento.billing_dunning_attempt
              SET state = ?, claim_token = NULL, lease_until = NULL,
                  provider_code = ?, provider_account_id = COALESCE(?, provider_account_id),
                  provider_reference = COALESCE(?, provider_reference),
                  next_attempt_at = ?, last_error_code = ?, updated_at = ?
              WHERE tenant_id = ? AND attempt_id = ? AND state = 'RECONCILING'
                AND claim_token = ? AND lease_until > ?
              """
            : """
              UPDATE agendamento.billing_dunning_attempt
              SET state = ?, claim_token = NULL, lease_until = NULL,
                  provider_code = ?, provider_account_id = COALESCE(?, provider_account_id),
                  provider_reference = COALESCE(?, provider_reference),
                  next_attempt_at = ?, last_error_code = ?, updated_at = ?
              WHERE tenant_id = ? AND attempt_id = ? AND send_claim_token = ?
                AND state IN ('SUBMITTING', 'OUTCOME_UNKNOWN', 'RECONCILING')
              """;
    boolean persisted;
    if (reconciliation) {
      persisted =
          jdbc.update(
                  sql,
                  nextState,
                  providerCode,
                  providerAccount,
                  providerReference,
                  nextAt == null ? null : Timestamp.from(nextAt),
                  errorCode,
                  Timestamp.from(now),
                  claim.tenantId(),
                  claim.attemptId(),
                  claim.claimToken(),
                  Timestamp.from(now))
              == 1;
    } else {
      persisted =
          jdbc.update(
                  sql,
                  nextState,
                  providerCode,
                  providerAccount,
                  providerReference,
                  nextAt == null ? null : Timestamp.from(nextAt),
                  errorCode,
                  Timestamp.from(now),
                  claim.tenantId(),
                  claim.attemptId(),
                  claim.claimToken())
              == 1;
    }
    if (persisted && providerCode != null && providerAccount != null && providerReference != null) {
      reconcilePaymentEvidence(providerCode, providerAccount, providerReference);
    }
    return persisted;
  }

  private static String stateFor(
      DunningChargeTransport.Outcome outcome, int attempts, int maxTechnicalAttempts) {
    return switch (outcome) {
      case ACCEPTED -> "ACCEPTED";
      case RETRYABLE_NO_EFFECT ->
          attempts >= maxTechnicalAttempts ? "FAILED_FINAL" : "RETRYABLE_NO_EFFECT";
      case DECLINED -> "DECLINED";
      case FAILED_FINAL -> "FAILED_FINAL";
      case OUTCOME_UNKNOWN -> "OUTCOME_UNKNOWN";
    };
  }

  private void recordEffects(
      UUID tenantId,
      UUID subscriptionId,
      UUID operationId,
      String action,
      String eventType,
      long before,
      long after) {
    UUID traceId = correlation.currentTraceId();
    String details =
        "{\"changed_fields\":[\"subscription\"],\"version_before\":"
            + before
            + ",\"version_after\":"
            + after
            + "}";
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            tenantId,
            null,
            "SYSTEM",
            "SUBSCRIPTION_LIFECYCLE",
            action,
            "SUBSCRIPTION",
            subscriptionId,
            "COMMITTED",
            operationId,
            traceId,
            details));
    outbox.record(
        new OutboxEvent(
            UUID.randomUUID(),
            tenantId,
            "SUBSCRIPTION",
            subscriptionId,
            after,
            eventType,
            1,
            operationId,
            traceId,
            "{\"subscriptionId\":\"" + subscriptionId + "\",\"version\":" + after + "}"));
  }

  private void lockReference(String provider, String account, String reference) {
    jdbc.query(
        "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
        resultSet -> null,
        provider + ":" + account + ":" + reference);
  }

  private static UUID operationId(UUID subscriptionId, String action, long version) {
    return UUID.nameUUIDFromBytes(
        (subscriptionId + ":" + action + ":v" + version).getBytes(StandardCharsets.UTF_8));
  }

  private static Subscription mapSubscription(ResultSet row, int index) throws SQLException {
    return new Subscription(
        row.getObject("subscription_id", UUID.class),
        row.getObject("tenant_id", UUID.class),
        row.getString("status"),
        instant(row, "trial_started_at"),
        instant(row, "trial_ends_at"),
        row.getString("plan_code"),
        instant(row, "paid_through_at"),
        instant(row, "grace_ends_at"),
        row.getLong("version"));
  }

  private static Instant instant(ResultSet row, String column) throws SQLException {
    Timestamp value = row.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private record AttemptRow(
      UUID attemptId,
      UUID externalIdempotencyKey,
      UUID cycleId,
      UUID subscriptionId,
      int logicalSequence,
      int technicalAttempts,
      Instant scheduledAt,
      String providerCode,
      String providerAccountId,
      String providerReference,
      String planCode,
      Instant periodEndsAt,
      Long amountMinorUnits,
      String currency,
      String termsVersion,
      String offerVersion) {}

  private record ContractTerms(
      Long amountMinorUnits,
      String currency,
      String termsVersion,
      String offerVersion,
      String planCode) {}

  private record PaymentAttemptRow(
      UUID attemptId,
      UUID cycleId,
      UUID subscriptionId,
      long lastProviderSequence,
      String attemptState,
      String cycleState,
      String subscriptionStatus,
      long subscriptionVersion,
      Instant currentPaidThrough,
      Long amountMinorUnits,
      String currency,
      String planCode) {}

  private record InboxPaymentEvent(
      String eventId,
      long sequence,
      String outcome,
      long amountMinorUnits,
      String currency,
      Instant occurredAt,
      Instant paidThroughAt) {}

  private record AnnualSubscriptionRow(
      UUID subscriptionId, long version, Instant paidThroughAt, String status, String planCode) {}

  private record AnnualNoticeRow(
      UUID noticeId,
      UUID subscriptionId,
      long subscriptionVersion,
      Instant paidThroughAt,
      Instant scheduledAt,
      String state) {}

  private record CycleRow(
      UUID cycleId, UUID subscriptionId, Instant graceEndsAt, String planCode) {}

  private record SendAuthorizationRow(
      String state,
      UUID claimToken,
      Instant leaseUntil,
      boolean everStarted,
      Instant scheduledAt,
      Instant nextAttemptAt,
      String cycleState,
      String subscriptionStatus,
      String planCode,
      Instant graceEndsAt,
      Instant periodEndsAt,
      Long amountMinorUnits,
      String currency,
      String termsVersion,
      String offerVersion) {}

  private record DueState(
      UUID subscriptionId, UUID tenantId, Instant dueAt, Instant graceEndsAt, long version) {}
}
