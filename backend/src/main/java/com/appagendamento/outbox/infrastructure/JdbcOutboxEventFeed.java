package com.appagendamento.outbox.infrastructure;

import com.appagendamento.outbox.api.OutboxEvent;
import com.appagendamento.outbox.api.OutboxEventFeed;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOutboxEventFeed implements OutboxEventFeed {

  private final JdbcTemplate jdbc;

  public JdbcOutboxEventFeed(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<OutboxEvent> unprocessedAppointmentEvents(String consumerName, int limit) {
    if (consumerName == null || limit < 1 || limit > 500) {
      throw new IllegalArgumentException("Consulta de eventos inválida");
    }
    return jdbc.query(
        """
        SELECT event_id, tenant_id, aggregate_type, aggregate_id, aggregate_version, event_type,
               schema_version, operation_id, correlation_id
        FROM agendamento.outbox_event e
        WHERE e.aggregate_type = 'APPOINTMENT'
          AND e.event_type IN ('AppointmentConfirmed', 'AppointmentRescheduled', 'AppointmentCancelled')
          AND e.schema_version = 1
          AND NOT EXISTS (
            SELECT 1 FROM agendamento.processed_event p
            WHERE p.consumer_name = ? AND p.event_id = e.event_id
          )
        ORDER BY e.occurred_at, e.event_id
        LIMIT ?
        """,
        (row, index) ->
            new OutboxEvent(
                row.getObject("event_id", java.util.UUID.class),
                row.getObject("tenant_id", java.util.UUID.class),
                row.getString("aggregate_type"),
                row.getObject("aggregate_id", java.util.UUID.class),
                row.getLong("aggregate_version"),
                row.getString("event_type"),
                row.getInt("schema_version"),
                row.getObject("operation_id", java.util.UUID.class),
                row.getObject("correlation_id", java.util.UUID.class),
                "{\"appointmentId\":\""
                    + row.getObject("aggregate_id", java.util.UUID.class)
                    + "\",\"version\":"
                    + row.getLong("aggregate_version")
                    + "}"),
        consumerName,
        limit);
  }
}
