package com.appagendamento.audit.infrastructure;

import com.appagendamento.audit.api.AuditEvent;
import com.appagendamento.audit.api.AuditEventRecorder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAuditEventRecorder implements AuditEventRecorder {

  private final JdbcTemplate jdbc;

  public JdbcAuditEventRecorder(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void record(AuditEvent event) {
    jdbc.update(
        """
        INSERT INTO agendamento.audit_event
          (event_id, tenant_id, actor_id, actor_type, actor_ref, action, target_type,
           target_id, result, correlation_id, trace_id, details)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
        """,
        event.eventId(),
        event.tenantId(),
        event.actorId(),
        event.actorType(),
        event.actorRef(),
        event.action(),
        event.targetType(),
        event.targetId(),
        event.result(),
        event.correlationId(),
        event.traceId(),
        event.detailsJson());
  }
}
