package com.appagendamento.clients.infrastructure;

import com.appagendamento.clients.application.ClientBookingRestrictionCommandRepository;
import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresClientBookingRestrictionCommandRepository
    implements ClientBookingRestrictionCommandRepository {

  private final JdbcTemplate jdbc;

  public PostgresClientBookingRestrictionCommandRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void lockTenant(UUID tenantId) {
    jdbc.query(
        "SELECT tenant_id FROM agendamento.tenant WHERE tenant_id = ? FOR UPDATE",
        (row, index) -> row.getObject("tenant_id", UUID.class),
        tenantId);
  }

  @Override
  public java.util.Optional<Restriction> active(UUID tenantId, UUID clientId, UUID restrictionId) {
    return jdbc
        .query(
            "SELECT restriction_id, version, count_at_activation, threshold_at_activation, policy_version "
                + "FROM agendamento.client_booking_restriction WHERE tenant_id = ? AND client_id = ? "
                + "AND restriction_id = ? AND status = 'ACTIVE' FOR UPDATE",
            (row, index) ->
                new Restriction(
                    row.getObject("restriction_id", UUID.class),
                    row.getLong("version"),
                    row.getInt("count_at_activation"),
                    row.getInt("threshold_at_activation"),
                    row.getLong("policy_version")),
            tenantId,
            clientId,
            restrictionId)
        .stream()
        .findFirst();
  }

  @Override
  public java.util.Optional<Restriction> activeForClient(UUID tenantId, UUID clientId) {
    return jdbc
        .query(
            "SELECT restriction_id, version, count_at_activation, threshold_at_activation, policy_version "
                + "FROM agendamento.client_booking_restriction WHERE tenant_id = ? AND client_id = ? "
                + "AND status = 'ACTIVE' FOR UPDATE",
            (row, index) ->
                new Restriction(
                    row.getObject("restriction_id", UUID.class),
                    row.getLong("version"),
                    row.getInt("count_at_activation"),
                    row.getInt("threshold_at_activation"),
                    row.getLong("policy_version")),
            tenantId,
            clientId)
        .stream()
        .findFirst();
  }

  @Override
  public java.util.Optional<NoShowCause> unusedContributingCause(
      UUID tenantId, UUID clientId, java.time.Instant asOf, java.time.Instant windowStart) {
    return jdbc
        .query(
            "SELECT h.appointment_id, h.history_id FROM agendamento.appointment_history h "
                + "JOIN agendamento.appointment a ON a.tenant_id = h.tenant_id AND a.appointment_id = h.appointment_id "
                + "WHERE h.tenant_id = ? AND a.client_id = ? AND h.to_status = 'NO_SHOW' "
                + "AND h.reason_code = 'APPOINTMENT_NO_SHOW' AND h.occurred_at >= ? AND h.occurred_at < ? "
                + "AND NOT EXISTS (SELECT 1 FROM agendamento.appointment_history correction "
                + "WHERE correction.tenant_id = h.tenant_id AND correction.corrects_history_id = h.history_id "
                + "AND correction.occurred_at <= ?) "
                + "AND NOT EXISTS (SELECT 1 FROM agendamento.client_booking_restriction used "
                + "WHERE used.tenant_id = h.tenant_id AND used.source_history_id = h.history_id) "
                + "ORDER BY h.occurred_at DESC, h.history_id DESC LIMIT 1",
            (row, index) ->
                new NoShowCause(
                    row.getObject("appointment_id", UUID.class),
                    row.getObject("history_id", UUID.class)),
            tenantId,
            clientId,
            Timestamp.from(windowStart),
            Timestamp.from(asOf),
            Timestamp.from(asOf))
        .stream()
        .findFirst();
  }

  @Override
  public java.util.Optional<UUID> activateFromReconciliation(
      UUID tenantId,
      UUID clientId,
      NoShowCause cause,
      UUID administratorId,
      java.time.Instant asOf,
      int count,
      int threshold,
      long policyVersion) {
    UUID restrictionId = UUID.randomUUID();
    var inserted =
        jdbc.query(
            "INSERT INTO agendamento.client_booking_restriction "
                + "(restriction_id, tenant_id, client_id, source_appointment_id, source_history_id, status, "
                + "evaluated_at, count_at_activation, threshold_at_activation, policy_version, version, created_at) "
                + "VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, 1, ?) ON CONFLICT DO NOTHING RETURNING restriction_id",
            (row, index) -> row.getObject("restriction_id", UUID.class),
            restrictionId,
            tenantId,
            clientId,
            cause.appointmentId(),
            cause.historyId(),
            Timestamp.from(asOf),
            count,
            threshold,
            policyVersion,
            Timestamp.from(asOf));
    if (inserted.isEmpty()) return java.util.Optional.empty();
    jdbc.update(
        "INSERT INTO agendamento.client_booking_restriction_history "
            + "(history_id, tenant_id, restriction_id, event_type, actor_type, actor_id, occurred_at, "
            + "count_at_decision, threshold_at_decision, policy_version, restriction_version, reason) "
            + "VALUES (?, ?, ?, 'RECONCILED', 'ADMIN', ?, ?, ?, ?, ?, 1, NULL)",
        UUID.randomUUID(),
        tenantId,
        restrictionId,
        administratorId,
        Timestamp.from(asOf),
        count,
        threshold,
        policyVersion);
    return java.util.Optional.of(restrictionId);
  }

  @Override
  public boolean lift(
      UUID tenantId,
      UUID clientId,
      UUID restrictionId,
      long expectedVersion,
      UUID administratorId,
      java.time.Instant occurredAt,
      int currentCount,
      String reason) {
    int updated =
        jdbc.update(
            "UPDATE agendamento.client_booking_restriction SET status = 'LIFTED', version = version + 1, "
                + "lifted_at = ?, lifted_by = ?, lift_reason = ? WHERE tenant_id = ? AND client_id = ? "
                + "AND restriction_id = ? AND status = 'ACTIVE' AND version = ?",
            Timestamp.from(occurredAt),
            administratorId,
            reason,
            tenantId,
            clientId,
            restrictionId,
            expectedVersion);
    if (updated != 1) return false;
    Restriction restriction = activeBeforeLift(tenantId, clientId, restrictionId, expectedVersion);
    jdbc.update(
        "INSERT INTO agendamento.client_booking_restriction_history "
            + "(history_id, tenant_id, restriction_id, event_type, actor_type, actor_id, occurred_at, "
            + "count_at_decision, threshold_at_decision, policy_version, restriction_version, reason) "
            + "VALUES (?, ?, ?, 'LIFTED', 'ADMIN', ?, ?, ?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        tenantId,
        restrictionId,
        administratorId,
        Timestamp.from(occurredAt),
        currentCount,
        restriction.thresholdAtActivation(),
        restriction.policyVersion(),
        expectedVersion + 1,
        reason);
    return true;
  }

  private Restriction activeBeforeLift(
      UUID tenantId, UUID clientId, UUID restrictionId, long expectedVersion) {
    return jdbc.queryForObject(
        "SELECT restriction_id, ? AS version, count_at_activation, threshold_at_activation, policy_version "
            + "FROM agendamento.client_booking_restriction WHERE tenant_id = ? AND client_id = ? AND restriction_id = ?",
        (row, index) ->
            new Restriction(
                row.getObject("restriction_id", UUID.class),
                row.getLong("version"),
                row.getInt("count_at_activation"),
                row.getInt("threshold_at_activation"),
                row.getLong("policy_version")),
        expectedVersion,
        tenantId,
        clientId,
        restrictionId);
  }
}
