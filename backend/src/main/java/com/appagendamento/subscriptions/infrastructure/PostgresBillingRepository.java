package com.appagendamento.subscriptions.infrastructure;

import com.appagendamento.appointments.api.TenantAgendaLock;
import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.audit.api.AuditEvent;
import com.appagendamento.audit.api.AuditEventRecorder;
import com.appagendamento.outbox.api.OutboxEvent;
import com.appagendamento.outbox.api.OutboxEventRecorder;
import com.appagendamento.subscriptions.api.AuthenticatedPaymentEvent;
import com.appagendamento.subscriptions.api.HostedCheckoutProvider.CheckoutSession;
import com.appagendamento.subscriptions.application.BillingRepository;
import com.appagendamento.subscriptions.application.SubscriptionDunningRepository;
import com.appagendamento.subscriptions.domain.CheckoutOperation;
import com.appagendamento.subscriptions.domain.PlanCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PostgresBillingRepository implements BillingRepository {

  private static final RowMapper<CheckoutOperation> OPERATION_MAPPER =
      PostgresBillingRepository::mapOperation;
  private final JdbcTemplate jdbc;
  private final OutboxEventRecorder outbox;
  private final AuditEventRecorder audit;
  private final AuditCorrelationId correlation;
  private final TenantAgendaLock agendaLock;
  private final SubscriptionDunningRepository dunning;

  public PostgresBillingRepository(
      JdbcTemplate jdbc,
      OutboxEventRecorder outbox,
      AuditEventRecorder audit,
      AuditCorrelationId correlation,
      TenantAgendaLock agendaLock,
      SubscriptionDunningRepository dunning) {
    this.jdbc = jdbc;
    this.outbox = outbox;
    this.audit = audit;
    this.correlation = correlation;
    this.agendaLock = agendaLock;
    this.dunning = dunning;
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void createCheckout(CheckoutOperation operation) {
    jdbc.update(
        """
        INSERT INTO agendamento.billing_checkout_operation
          (operation_id, tenant_id, plan_code, amount_minor_units, currency, terms_version,
           offer_version, expected_subscription_version, provider_code, provider_account_id, state)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'REQUESTED')
        """,
        operation.operationId(),
        operation.tenantId(),
        operation.planCode().name(),
        operation.amountMinorUnits(),
        operation.currency(),
        operation.termsVersion(),
        operation.offerVersion(),
        operation.expectedSubscriptionVersion(),
        operation.providerCode(),
        operation.providerAccountId());
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<CheckoutOperation> findCheckout(UUID tenantId, UUID operationId) {
    return jdbc
        .query(
            """
            SELECT * FROM agendamento.billing_checkout_operation
            WHERE tenant_id = ? AND operation_id = ?
            """,
            OPERATION_MAPPER,
            tenantId,
            operationId)
        .stream()
        .findFirst();
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Optional<UUID> claimProviderCall(UUID tenantId, UUID operationId) {
    UUID token = UUID.randomUUID();
    List<UUID> claimed =
        jdbc.query(
            """
            UPDATE agendamento.billing_checkout_operation
            SET state = 'SUBMITTING', provider_claim_token = ?, updated_at = clock_timestamp()
            WHERE tenant_id = ? AND operation_id = ? AND state = 'REQUESTED'
            RETURNING operation_id
            """,
            (row, index) -> row.getObject("operation_id", UUID.class),
            token,
            tenantId,
            operationId);
    return claimed.isEmpty() ? Optional.empty() : Optional.of(token);
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void markCheckoutUnknown(UUID tenantId, UUID operationId, UUID claimToken) {
    jdbc.update(
        """
        UPDATE agendamento.billing_checkout_operation
        SET state = 'OUTCOME_UNKNOWN', provider_claim_token = NULL, updated_at = clock_timestamp()
        WHERE tenant_id = ? AND operation_id = ? AND state = 'SUBMITTING' AND provider_claim_token = ?
        """,
        tenantId,
        operationId,
        claimToken);
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void markCheckoutReady(
      UUID tenantId,
      UUID operationId,
      UUID claimToken,
      String providerCode,
      String providerAccountId,
      CheckoutSession session) {
    lockReference(providerCode, providerAccountId, session.providerReference());
    int updated =
        jdbc.update(
            """
            UPDATE agendamento.billing_checkout_operation
            SET state = 'CHECKOUT_READY', provider_reference = ?, provider_claim_token = NULL,
                updated_at = clock_timestamp()
            WHERE tenant_id = ? AND operation_id = ? AND state = 'SUBMITTING'
              AND provider_claim_token = ? AND provider_code = ? AND provider_account_id = ?
            """,
            session.providerReference(),
            tenantId,
            operationId,
            claimToken,
            providerCode,
            providerAccountId);
    if (updated == 1)
      reconcileUnlinked(providerCode, providerAccountId, session.providerReference());
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void reconcileAcceptedCheckout(
      UUID tenantId,
      UUID operationId,
      String providerCode,
      String providerAccountId,
      CheckoutSession session) {
    lockReference(providerCode, providerAccountId, session.providerReference());
    jdbc.update(
        """
        UPDATE agendamento.billing_checkout_operation
        SET state = 'CHECKOUT_READY', provider_reference = ?, provider_claim_token = NULL,
            updated_at = clock_timestamp()
        WHERE tenant_id = ? AND operation_id = ? AND provider_code = ? AND provider_account_id = ?
          AND state IN ('SUBMITTING', 'OUTCOME_UNKNOWN', 'CHECKOUT_READY')
          AND (provider_reference IS NULL OR provider_reference = ?)
        """,
        session.providerReference(),
        tenantId,
        operationId,
        providerCode,
        providerAccountId,
        session.providerReference());
    reconcileUnlinked(providerCode, providerAccountId, session.providerReference());
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<CheckoutOperation> findCheckoutByProviderReference(
      String providerCode, String providerAccountId, String providerReference) {
    return jdbc
        .query(
            """
            SELECT * FROM agendamento.billing_checkout_operation
            WHERE provider_code = ? AND provider_account_id = ? AND provider_reference = ?
            """,
            OPERATION_MAPPER,
            providerCode,
            providerAccountId,
            providerReference)
        .stream()
        .findFirst();
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public boolean recordAuthenticatedEvent(AuthenticatedPaymentEvent event) {
    lockReference(event.providerCode(), event.providerAccountId(), event.providerReference());
    List<String> inserted =
        jdbc.query(
            """
            INSERT INTO agendamento.billing_event_inbox
              (provider_code, provider_account_id, provider_event_id, provider_reference,
               provider_sequence, outcome, amount_minor_units, currency, occurred_at, paid_through_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (provider_code, provider_account_id, provider_event_id) DO NOTHING
            RETURNING provider_event_id
            """,
            (row, index) -> row.getString("provider_event_id"),
            event.providerCode(),
            event.providerAccountId(),
            event.providerEventId(),
            event.providerReference(),
            event.providerSequence(),
            event.outcome(),
            event.amountMinorUnits(),
            event.currency(),
            Timestamp.from(event.occurredAt()),
            event.paidThroughAt() == null ? null : Timestamp.from(event.paidThroughAt()));
    reconcileUnlinked(event.providerCode(), event.providerAccountId(), event.providerReference());
    dunning.reconcilePaymentEvidence(
        event.providerCode(), event.providerAccountId(), event.providerReference());
    return !inserted.isEmpty();
  }

  @Override
  @Transactional(readOnly = true)
  public List<CheckoutOperation> findOpenCheckouts(UUID tenantId) {
    return jdbc.query(
        """
        SELECT * FROM agendamento.billing_checkout_operation
        WHERE tenant_id = ? AND state IN ('REQUESTED', 'SUBMITTING', 'CHECKOUT_READY', 'OUTCOME_UNKNOWN')
        ORDER BY created_at DESC, operation_id DESC
        """,
        OPERATION_MAPPER,
        tenantId);
  }

  private void reconcileUnlinked(String providerCode, String accountId, String reference) {
    Boolean dunningReferenceExists =
        jdbc.queryForObject(
            """
            SELECT EXISTS (
              SELECT 1 FROM agendamento.billing_dunning_attempt
              WHERE provider_code = ? AND provider_account_id = ? AND provider_reference = ?
            )
            """,
            Boolean.class,
            providerCode,
            accountId,
            reference);
    if (Boolean.TRUE.equals(dunningReferenceExists)) return;
    CheckoutOperation operation =
        jdbc
            .query(
                """
                SELECT * FROM agendamento.billing_checkout_operation
                WHERE provider_code = ? AND provider_account_id = ? AND provider_reference = ?
                  AND state IN ('CHECKOUT_READY', 'OUTCOME_UNKNOWN', 'CONFIRMED', 'DECLINED')
                FOR UPDATE
                """,
                OPERATION_MAPPER,
                providerCode,
                accountId,
                reference)
            .stream()
            .findFirst()
            .orElse(null);
    if (operation == null) return;

    List<InboxEvent> pending =
        jdbc.query(
            """
            SELECT provider_event_id, provider_sequence, outcome, amount_minor_units, currency,
                   occurred_at, paid_through_at
            FROM agendamento.billing_event_inbox
            WHERE provider_code = ? AND provider_account_id = ? AND provider_reference = ?
              AND operation_id IS NULL
            ORDER BY provider_sequence, provider_event_id
            FOR UPDATE
            """,
            (row, index) ->
                new InboxEvent(
                    row.getString("provider_event_id"),
                    row.getLong("provider_sequence"),
                    row.getString("outcome"),
                    row.getLong("amount_minor_units"),
                    row.getString("currency"),
                    row.getTimestamp("occurred_at").toInstant(),
                    row.getTimestamp("paid_through_at") == null
                        ? null
                        : row.getTimestamp("paid_through_at").toInstant()),
            providerCode,
            accountId,
            reference);

    for (InboxEvent event : pending) {
      jdbc.update(
          """
          UPDATE agendamento.billing_event_inbox
          SET tenant_id = ?, operation_id = ?, disposition = 'PENDING'
          WHERE provider_code = ? AND provider_account_id = ? AND provider_event_id = ?
            AND operation_id IS NULL
          """,
          operation.tenantId(),
          operation.operationId(),
          providerCode,
          accountId,
          event.eventId());
      apply(operation, event, providerCode, accountId);
      operation = findCheckoutForUpdate(operation.tenantId(), operation.operationId());
    }
  }

  private CheckoutOperation findCheckoutForUpdate(UUID tenantId, UUID operationId) {
    return jdbc.queryForObject(
        """
        SELECT * FROM agendamento.billing_checkout_operation
        WHERE tenant_id = ? AND operation_id = ? FOR UPDATE
        """,
        OPERATION_MAPPER,
        tenantId,
        operationId);
  }

  private void apply(
      CheckoutOperation operation, InboxEvent event, String provider, String account) {
    if (event.sequence() <= operation.lastProviderSequence()) {
      setDisposition(provider, account, event.eventId(), "OLDER", operation.lastProviderSequence());
      return;
    }
    boolean termsMatch =
        event.amountMinorUnits() == operation.amountMinorUnits()
            && event.currency().equals(operation.currency());
    if (!termsMatch) {
      jdbc.update(
          "UPDATE agendamento.billing_event_inbox SET disposition = 'MISMATCHED' WHERE provider_code = ? AND provider_account_id = ? AND provider_event_id = ?",
          provider,
          account,
          event.eventId());
      return;
    }
    if (event.outcome().equals("PENDING")) {
      setDisposition(provider, account, event.eventId(), "PENDING", event.sequence());
      return;
    }
    if (event.outcome().equals("DECLINED")) {
      jdbc.update(
          """
          UPDATE agendamento.billing_checkout_operation
          SET state = CASE WHEN state = 'CONFIRMED' THEN state ELSE 'DECLINED' END,
              last_provider_sequence = ?, updated_at = clock_timestamp()
          WHERE operation_id = ?
          """,
          event.sequence(),
          operation.operationId());
      jdbc.update(
          "UPDATE agendamento.billing_event_inbox SET disposition = 'DECLINED' WHERE provider_code = ? AND provider_account_id = ? AND provider_event_id = ?",
          provider,
          account,
          event.eventId());
      return;
    }

    if (!operation.state().equals("CONFIRMED")) {
      boolean applied = activateSubscription(operation, event);
      jdbc.update(
          """
          UPDATE agendamento.billing_checkout_operation
          SET state = 'CONFIRMED', last_provider_sequence = ?, updated_at = clock_timestamp()
          WHERE operation_id = ?
          """,
          event.sequence(),
          operation.operationId());
      jdbc.update(
          "UPDATE agendamento.billing_event_inbox SET disposition = ? WHERE provider_code = ? AND provider_account_id = ? AND provider_event_id = ?",
          applied ? "APPLIED" : "OLDER",
          provider,
          account,
          event.eventId());
    } else {
      setDisposition(provider, account, event.eventId(), "OLDER", event.sequence());
    }
  }

  private boolean activateSubscription(CheckoutOperation operation, InboxEvent event) {
    if (event.paidThroughAt() == null) return false;
    agendaLock.acquire(operation.tenantId());
    List<SubscriptionState> current =
        jdbc.query(
            "SELECT subscription_id, status, version, paid_through_at FROM agendamento.subscription WHERE tenant_id = ? FOR UPDATE",
            (row, index) ->
                new SubscriptionState(
                    row.getObject("subscription_id", UUID.class),
                    row.getString("status"),
                    row.getLong("version"),
                    row.getTimestamp("paid_through_at") == null
                        ? null
                        : row.getTimestamp("paid_through_at").toInstant()),
            operation.tenantId());
    long newVersion;
    String fromStatus;
    UUID subscriptionId;
    if (current.isEmpty()) {
      subscriptionId = UUID.randomUUID();
      newVersion = 1;
      fromStatus = null;
      jdbc.update(
          """
          INSERT INTO agendamento.subscription
            (subscription_id, tenant_id, status, trial_started_at, trial_ends_at, version, plan_code, paid_through_at)
          VALUES (?, ?, 'ACTIVE', NULL, NULL, 1, ?, ?)
          """,
          subscriptionId,
          operation.tenantId(),
          operation.planCode().name(),
          Timestamp.from(event.paidThroughAt()));
    } else {
      SubscriptionState state = current.getFirst();
      if (state.status().equals("ACTIVE")
          || (state.paidThroughAt() != null
              && !event.paidThroughAt().isAfter(state.paidThroughAt()))) {
        jdbc.update(
            "UPDATE agendamento.billing_checkout_operation SET last_provider_sequence = ?, updated_at = clock_timestamp() WHERE operation_id = ?",
            event.sequence(),
            operation.operationId());
        jdbc.update(
            "UPDATE agendamento.billing_event_inbox SET disposition = 'OLDER' WHERE provider_code = ? AND provider_account_id = ? AND provider_event_id = ?",
            operation.providerCode(),
            operation.providerAccountId(),
            event.eventId());
        return false;
      }
      fromStatus = state.status();
      newVersion = state.version() + 1;
      subscriptionId = state.subscriptionId();
      int changed =
          jdbc.update(
              """
              UPDATE agendamento.subscription
              SET status = 'ACTIVE', plan_code = ?, paid_through_at = ?, grace_ends_at = NULL,
                  version = version + 1
              WHERE tenant_id = ? AND subscription_id = ? AND version = ?
                AND status IN ('TRIALING', 'PAST_DUE', 'SUSPENDED')
              """,
              operation.planCode().name(),
              Timestamp.from(event.paidThroughAt()),
              operation.tenantId(),
              subscriptionId,
              state.version());
      if (changed != 1) throw new IllegalStateException("CAS da assinatura foi perdido");
      if (state.status().equals("PAST_DUE") || state.status().equals("SUSPENDED")) {
        dunning.closeCyclesForPayment(operation.tenantId(), subscriptionId, event.occurredAt());
      }
    }
    jdbc.update(
        """
        INSERT INTO agendamento.billing_subscription_contract
          (subscription_id, tenant_id, source_operation_id, amount_minor_units, currency,
           terms_version, offer_version)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (subscription_id) DO UPDATE
        SET source_operation_id = EXCLUDED.source_operation_id,
            amount_minor_units = EXCLUDED.amount_minor_units,
            currency = EXCLUDED.currency,
            terms_version = EXCLUDED.terms_version,
            offer_version = EXCLUDED.offer_version,
            recorded_at = clock_timestamp()
        """,
        subscriptionId,
        operation.tenantId(),
        operation.operationId(),
        operation.amountMinorUnits(),
        operation.currency(),
        operation.termsVersion(),
        operation.offerVersion());
    jdbc.update(
        """
        INSERT INTO agendamento.subscription_history
          (event_id, tenant_id, subscription_id, event_type, from_status, to_status,
           event_at, version, operation_id)
        VALUES (?, ?, ?, 'PAYMENT_CONFIRMED', ?, 'ACTIVE', ?, ?, ?)
        """,
        UUID.randomUUID(),
        operation.tenantId(),
        subscriptionId,
        fromStatus,
        Timestamp.from(event.occurredAt()),
        newVersion,
        operation.operationId());
    UUID traceId = correlation.currentTraceId();
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            operation.tenantId(),
            null,
            "SYSTEM",
            "SUBSCRIPTION_LIFECYCLE",
            "SUBSCRIPTION_PAYMENT_CONFIRMED",
            "SUBSCRIPTION",
            subscriptionId,
            "COMMITTED",
            operation.operationId(),
            traceId,
            "{\"changed_fields\":[\"subscription\"],\"version_before\":"
                + (newVersion - 1)
                + ",\"version_after\":"
                + newVersion
                + "}"));
    outbox.record(
        new OutboxEvent(
            UUID.randomUUID(),
            operation.tenantId(),
            "SUBSCRIPTION",
            subscriptionId,
            newVersion,
            "SubscriptionReactivated",
            1,
            operation.operationId(),
            traceId,
            "{\"subscriptionId\":\"" + subscriptionId + "\",\"version\":" + newVersion + "}"));
    return true;
  }

  private void setDisposition(
      String provider, String account, String eventId, String disposition, long sequence) {
    jdbc.update(
        "UPDATE agendamento.billing_event_inbox SET disposition = ? WHERE provider_code = ? AND provider_account_id = ? AND provider_event_id = ?",
        disposition,
        provider,
        account,
        eventId);
    jdbc.update(
        "UPDATE agendamento.billing_checkout_operation SET last_provider_sequence = ?, updated_at = clock_timestamp() WHERE provider_code = ? AND provider_account_id = ? AND provider_reference = ?",
        sequence,
        provider,
        account,
        jdbc.queryForObject(
            "SELECT provider_reference FROM agendamento.billing_event_inbox WHERE provider_code = ? AND provider_account_id = ? AND provider_event_id = ?",
            String.class,
            provider,
            account,
            eventId));
  }

  private void lockReference(String provider, String account, String reference) {
    jdbc.query(
        "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
        resultSet -> null,
        provider + ":" + account + ":" + reference);
  }

  private static CheckoutOperation mapOperation(ResultSet row, int index) throws SQLException {
    return new CheckoutOperation(
        row.getObject("operation_id", UUID.class),
        row.getObject("tenant_id", UUID.class),
        PlanCode.valueOf(row.getString("plan_code")),
        row.getLong("amount_minor_units"),
        row.getString("currency").trim(),
        row.getString("terms_version"),
        row.getString("offer_version"),
        row.getLong("expected_subscription_version"),
        row.getString("state"),
        row.getString("provider_code"),
        row.getString("provider_account_id"),
        row.getString("provider_reference"),
        row.getLong("last_provider_sequence"),
        row.getTimestamp("created_at").toInstant());
  }

  private record InboxEvent(
      String eventId,
      long sequence,
      String outcome,
      long amountMinorUnits,
      String currency,
      java.time.Instant occurredAt,
      java.time.Instant paidThroughAt) {}

  private record SubscriptionState(
      UUID subscriptionId, String status, long version, java.time.Instant paidThroughAt) {}
}
