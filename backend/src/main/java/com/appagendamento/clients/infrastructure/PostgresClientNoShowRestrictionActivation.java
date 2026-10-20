package com.appagendamento.clients.infrastructure;

import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.audit.api.AuditEvent;
import com.appagendamento.audit.api.AuditEventRecorder;
import com.appagendamento.clients.api.ClientNoShowRestrictionActivation;
import com.appagendamento.clients.application.ClientNoShowPolicyRepository;
import com.appagendamento.outbox.api.OutboxEvent;
import com.appagendamento.outbox.api.OutboxEventRecorder;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PostgresClientNoShowRestrictionActivation
    implements ClientNoShowRestrictionActivation {

  private final JdbcTemplate jdbc;
  private final ClientNoShowPolicyRepository policies;
  private final AuditEventRecorder audit;
  private final AuditCorrelationId correlation;
  private final OutboxEventRecorder outbox;

  public PostgresClientNoShowRestrictionActivation(
      JdbcTemplate jdbc,
      ClientNoShowPolicyRepository policies,
      AuditEventRecorder audit,
      AuditCorrelationId correlation,
      OutboxEventRecorder outbox) {
    this.jdbc = jdbc;
    this.policies = policies;
    this.audit = audit;
    this.correlation = correlation;
    this.outbox = outbox;
  }

  @Override
  @Transactional
  public void evaluateAfterNoShow(UUID tenantId, UUID clientId, UUID appointmentId, Instant asOf) {
    var policy = policies.findPolicy(tenantId).orElse(null);
    if (policy == null) return;
    Timestamp windowStart = Timestamp.from(asOf.atZone(ZoneOffset.UTC).minusMonths(12).toInstant());
    Timestamp evaluationTime = Timestamp.from(asOf);
    Integer count =
        jdbc.queryForObject(
            "SELECT count(DISTINCT a.appointment_id) FROM agendamento.appointment_history h "
                + "JOIN agendamento.appointment a ON a.tenant_id = h.tenant_id AND a.appointment_id = h.appointment_id "
                + "WHERE h.tenant_id = ? AND a.client_id = ? AND h.to_status = 'NO_SHOW' "
                + "AND h.reason_code = 'APPOINTMENT_NO_SHOW' AND h.occurred_at >= ? AND h.occurred_at < ? "
                + "AND NOT EXISTS (SELECT 1 FROM agendamento.appointment_history correction "
                + "WHERE correction.tenant_id = h.tenant_id AND correction.corrects_history_id = h.history_id "
                + "AND correction.occurred_at <= ?)",
            Integer.class,
            tenantId,
            clientId,
            windowStart,
            evaluationTime,
            evaluationTime);
    if (count == null || count < policy.threshold()) return;
    UUID sourceHistoryId =
        jdbc
            .query(
                "SELECT h.history_id FROM agendamento.appointment_history h "
                    + "WHERE h.tenant_id = ? AND h.appointment_id = ? AND h.to_status = 'NO_SHOW' "
                    + "AND h.reason_code = 'APPOINTMENT_NO_SHOW' AND h.occurred_at >= ? AND h.occurred_at < ? "
                    + "AND NOT EXISTS (SELECT 1 FROM agendamento.appointment_history correction "
                    + "WHERE correction.tenant_id = h.tenant_id AND correction.corrects_history_id = h.history_id "
                    + "AND correction.occurred_at <= ?) ORDER BY h.occurred_at DESC, h.history_id DESC LIMIT 1",
                (row, index) -> row.getObject("history_id", UUID.class),
                tenantId,
                appointmentId,
                windowStart,
                evaluationTime,
                evaluationTime)
            .stream()
            .findFirst()
            .orElse(null);
    if (sourceHistoryId == null) return;
    UUID restrictionId = UUID.randomUUID();
    List<UUID> inserted =
        jdbc.query(
            "INSERT INTO agendamento.client_booking_restriction "
                + "(restriction_id, tenant_id, client_id, source_appointment_id, source_history_id, status, "
                + "evaluated_at, count_at_activation, threshold_at_activation, policy_version, version, created_at) "
                + "VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, 1, clock_timestamp()) "
                + "ON CONFLICT DO NOTHING RETURNING restriction_id",
            (row, index) -> row.getObject("restriction_id", UUID.class),
            restrictionId,
            tenantId,
            clientId,
            appointmentId,
            sourceHistoryId,
            Timestamp.from(asOf),
            count,
            policy.threshold(),
            policy.version());
    if (inserted.isEmpty()) return;
    UUID historyId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO agendamento.client_booking_restriction_history "
            + "(history_id, tenant_id, restriction_id, event_type, actor_type, actor_id, occurred_at, "
            + "count_at_decision, threshold_at_decision, policy_version, restriction_version) "
            + "VALUES (?, ?, ?, 'ACTIVATED', 'SYSTEM', NULL, ?, ?, ?, ?, 1)",
        historyId,
        tenantId,
        restrictionId,
        Timestamp.from(asOf),
        count,
        policy.threshold(),
        policy.version());
    UUID traceId = correlation.currentTraceId();
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            tenantId,
            null,
            "SYSTEM",
            "NO_SHOW_POLICY",
            "CLIENT_SELF_BOOKING_BLOCKED",
            "CLIENT_RESTRICTION",
            restrictionId,
            "COMMITTED",
            traceId,
            traceId,
            "{\"changed_fields\":[\"self_booking_restriction\"],\"count\":"
                + count
                + ",\"threshold\":"
                + policy.threshold()
                + ",\"policy_version\":"
                + policy.version()
                + ",\"version_after\":1}"));
    outbox.record(
        new OutboxEvent(
            UUID.randomUUID(),
            tenantId,
            "CLIENT_RESTRICTION",
            restrictionId,
            1,
            "ClientSelfBookingBlocked",
            1,
            historyId,
            traceId,
            "{\"clientId\":\""
                + clientId
                + "\",\"restrictionId\":\""
                + restrictionId
                + "\",\"count\":"
                + count
                + ",\"threshold\":"
                + policy.threshold()
                + ",\"policyVersion\":"
                + policy.version()
                + ",\"version\":1}"));
  }
}
