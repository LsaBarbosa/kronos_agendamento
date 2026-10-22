package com.appagendamento.subscriptions.infrastructure;

import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.audit.api.AuditEvent;
import com.appagendamento.audit.api.AuditEventRecorder;
import com.appagendamento.outbox.api.OutboxEvent;
import com.appagendamento.outbox.api.OutboxEventRecorder;
import com.appagendamento.subscriptions.application.SubscriptionDunningRepository;
import com.appagendamento.subscriptions.domain.DunningAttemptView;
import com.appagendamento.subscriptions.domain.Subscription;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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

  public PostgresSubscriptionDunningRepository(
      JdbcTemplate jdbc,
      AuditEventRecorder audit,
      OutboxEventRecorder outbox,
      AuditCorrelationId correlation) {
    this.jdbc = jdbc;
    this.audit = audit;
    this.outbox = outbox;
    this.correlation = correlation;
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
            SET status = 'PAST_DUE', grace_ends_at = paid_through_at + INTERVAL '7 days',
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
    for (int sequence : List.of(0, 3, 6)) {
      UUID attemptId =
          UUID.nameUUIDFromBytes(
              (cycleId + ":logical-attempt:" + sequence).getBytes(StandardCharsets.UTF_8));
      jdbc.update(
          """
          INSERT INTO agendamento.billing_dunning_attempt
            (attempt_id, tenant_id, cycle_id, logical_sequence, scheduled_at, state, updated_at)
          VALUES (?, ?, ?, ?, ?, 'SCHEDULED', ?)
          """,
          attemptId,
          due.tenantId(),
          cycleId,
          sequence,
          Timestamp.from(due.dueAt().plus(Duration.ofDays(sequence))),
          Timestamp.from(asOf));
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
        SET state = 'CANCELLED', updated_at = ?
        WHERE tenant_id = ? AND state IN ('SCHEDULED', 'BLOCKED_CONFIGURATION')
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
        UPDATE agendamento.billing_dunning_attempt SET state = 'CANCELLED', updated_at = ?
        WHERE tenant_id = ? AND state IN ('SCHEDULED', 'BLOCKED_CONFIGURATION')
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
  public List<DunningAttemptView> listAttempts(UUID tenantId) {
    return jdbc.query(
        """
        SELECT a.logical_sequence, a.scheduled_at, a.state
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
                row.getString("state")),
        tenantId);
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

  private record DueState(
      UUID subscriptionId, UUID tenantId, Instant dueAt, Instant graceEndsAt, long version) {}
}
