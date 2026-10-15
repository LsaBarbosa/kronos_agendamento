package com.appagendamento.outbox.infrastructure;

import com.appagendamento.outbox.api.OutboxEvent;
import com.appagendamento.outbox.api.OutboxEventRecorder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOutboxEventRecorder implements OutboxEventRecorder {

  private final JdbcTemplate jdbc;

  public JdbcOutboxEventRecorder(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void record(OutboxEvent event) {
    jdbc.update(
        """
        INSERT INTO agendamento.outbox_event
          (event_id, tenant_id, aggregate_type, aggregate_id, aggregate_version,
           event_type, schema_version, operation_id, correlation_id, payload)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
        """,
        event.eventId(),
        event.tenantId(),
        event.aggregateType(),
        event.aggregateId(),
        event.aggregateVersion(),
        event.eventType(),
        event.schemaVersion(),
        event.operationId(),
        event.correlationId(),
        event.payloadJson());
  }
}
