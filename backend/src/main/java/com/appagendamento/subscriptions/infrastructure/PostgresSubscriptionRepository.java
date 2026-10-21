package com.appagendamento.subscriptions.infrastructure;

import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.audit.api.AuditEvent;
import com.appagendamento.audit.api.AuditEventRecorder;
import com.appagendamento.subscriptions.application.SubscriptionRepository;
import com.appagendamento.subscriptions.domain.Subscription;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresSubscriptionRepository implements SubscriptionRepository {

  private static final RowMapper<Subscription> MAPPER = PostgresSubscriptionRepository::map;

  private final JdbcTemplate jdbc;
  private final AuditEventRecorder audit;
  private final AuditCorrelationId correlation;

  public PostgresSubscriptionRepository(
      JdbcTemplate jdbc, AuditEventRecorder audit, AuditCorrelationId correlation) {
    this.jdbc = jdbc;
    this.audit = audit;
    this.correlation = correlation;
  }

  @Override
  public boolean grantTrial(
      UUID tenantId,
      UUID subscriptionId,
      String identityDigest,
      Instant startsAt,
      Instant endsAt,
      UUID operationId) {
    List<UUID> grants =
        jdbc.query(
            """
            INSERT INTO agendamento.commercial_trial_grant
              (grant_id, tenant_id, commercial_identity_digest, operation_id, granted_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (commercial_identity_digest) DO NOTHING
            RETURNING grant_id
            """,
            (row, index) -> row.getObject("grant_id", UUID.class),
            UUID.randomUUID(),
            tenantId,
            identityDigest,
            operationId,
            Timestamp.from(startsAt));
    if (grants.isEmpty()) return false;

    jdbc.update(
        """
        INSERT INTO agendamento.subscription
          (subscription_id, tenant_id, status, trial_started_at, trial_ends_at, version)
        VALUES (?, ?, 'TRIALING', ?, ?, 1)
        """,
        subscriptionId,
        tenantId,
        Timestamp.from(startsAt),
        Timestamp.from(endsAt));
    jdbc.update(
        """
        INSERT INTO agendamento.subscription_history
          (event_id, tenant_id, subscription_id, event_type, from_status, to_status,
           event_at, version, operation_id)
        VALUES (?, ?, ?, 'TRIAL_GRANTED', NULL, 'TRIALING', ?, 1, ?)
        """,
        UUID.randomUUID(),
        tenantId,
        subscriptionId,
        Timestamp.from(startsAt),
        operationId);
    recordAudit(tenantId, subscriptionId, "SUBSCRIPTION_TRIAL_GRANTED", 0, 1);
    return true;
  }

  @Override
  public Optional<Subscription> findCurrent(UUID tenantId) {
    return jdbc
        .query(
            """
            SELECT subscription_id, tenant_id, status, trial_started_at, trial_ends_at,
                   plan_code, paid_through_at, version
            FROM agendamento.subscription WHERE tenant_id = ?
            """,
            MAPPER,
            tenantId)
        .stream()
        .findFirst();
  }

  @Override
  public boolean expireTrial(
      UUID tenantId,
      UUID subscriptionId,
      long expectedVersion,
      Instant expiredAt,
      UUID operationId) {
    int changed =
        jdbc.update(
            """
            UPDATE agendamento.subscription
            SET status = 'SUSPENDED', version = version + 1
            WHERE tenant_id = ? AND subscription_id = ? AND status = 'TRIALING'
              AND version = ? AND trial_ends_at <= ?
            """,
            tenantId,
            subscriptionId,
            expectedVersion,
            Timestamp.from(expiredAt));
    if (changed != 1) return false;
    jdbc.update(
        """
        INSERT INTO agendamento.subscription_history
          (event_id, tenant_id, subscription_id, event_type, from_status, to_status,
           event_at, version, operation_id)
        VALUES (?, ?, ?, 'TRIAL_EXPIRED', 'TRIALING', 'SUSPENDED', ?, 2, ?)
        """,
        UUID.randomUUID(),
        tenantId,
        subscriptionId,
        Timestamp.from(expiredAt),
        operationId);
    recordAudit(tenantId, subscriptionId, "SUBSCRIPTION_TRIAL_EXPIRED", 1, 2);
    return true;
  }

  @Override
  public List<Subscription> findExpiredTrials(Instant asOf, int limit) {
    return jdbc.query(
        """
        SELECT subscription_id, tenant_id, status, trial_started_at, trial_ends_at,
               plan_code, paid_through_at, version
        FROM agendamento.subscription
        WHERE status = 'TRIALING' AND trial_ends_at <= ?
        ORDER BY trial_ends_at, tenant_id
        LIMIT ?
        """,
        MAPPER,
        Timestamp.from(asOf),
        limit);
  }

  private void recordAudit(
      UUID tenantId, UUID subscriptionId, String action, long before, long after) {
    UUID traceId = correlation.currentTraceId();
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
            traceId,
            traceId,
            "{\"changed_fields\":[\"subscription\"],\"version_before\":"
                + before
                + ",\"version_after\":"
                + after
                + "}"));
  }

  private static Subscription map(ResultSet row, int index) throws SQLException {
    return new Subscription(
        row.getObject("subscription_id", UUID.class),
        row.getObject("tenant_id", UUID.class),
        row.getString("status"),
        row.getTimestamp("trial_started_at") == null
            ? null
            : row.getTimestamp("trial_started_at").toInstant(),
        row.getTimestamp("trial_ends_at") == null
            ? null
            : row.getTimestamp("trial_ends_at").toInstant(),
        row.getString("plan_code"),
        row.getTimestamp("paid_through_at") == null
            ? null
            : row.getTimestamp("paid_through_at").toInstant(),
        row.getLong("version"));
  }
}
