package com.appagendamento.outbox.infrastructure;

import com.appagendamento.outbox.application.IdempotentEventConsumer;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JdbcIdempotentEventConsumer implements IdempotentEventConsumer {

  private final JdbcTemplate jdbc;

  public JdbcIdempotentEventConsumer(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional
  public boolean process(String consumerName, UUID eventId, Runnable localEffect) {
    List<UUID> inserted =
        jdbc.query(
            """
            INSERT INTO agendamento.processed_event (consumer_name, event_id)
            VALUES (?, ?)
            ON CONFLICT (consumer_name, event_id) DO NOTHING
            RETURNING event_id
            """,
            (row, index) -> row.getObject("event_id", UUID.class),
            consumerName,
            eventId);
    if (inserted.isEmpty()) {
      return false;
    }
    localEffect.run();
    return true;
  }
}
