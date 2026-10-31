package com.appagendamento.outbox.infrastructure;

import com.appagendamento.outbox.api.OutboxRetentionFactsQuery;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresOutboxRetentionFactsQuery implements OutboxRetentionFactsQuery {

  private static final int LIMIT = 10_000;
  private static final int QUERY_TIMEOUT_SECONDS = 2;
  private final JdbcTemplate jdbc;

  public PostgresOutboxRetentionFactsQuery(JdbcTemplate jdbc) {
    this.jdbc = new JdbcTemplate(jdbc.getDataSource());
    this.jdbc.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
  }

  @Override
  public Facts query(UUID tenantId) {
    return jdbc.queryForObject(
        """
        SELECT
          (SELECT count(*) FROM (SELECT 1 FROM agendamento.outbox_event
            WHERE tenant_id = ? LIMIT ?) sampled) AS events,
          (SELECT count(*) FROM (SELECT 1 FROM agendamento.outbox_event
            WHERE tenant_id = ? AND state IN ('PENDING','CLAIMED','RETRY') LIMIT ?) sampled)
            AS pending,
          (SELECT count(*) FROM (SELECT 1 FROM agendamento.outbox_event
            WHERE tenant_id = ? AND state IN ('DEAD_LETTER','RECONCILIATION_REQUIRED')
            LIMIT ?) sampled) AS reconciliation
        """,
        (row, index) ->
            new Facts(
                count(row.getInt("events")),
                count(row.getInt("pending")),
                count(row.getInt("reconciliation"))),
        tenantId,
        LIMIT + 1,
        tenantId,
        LIMIT + 1,
        tenantId,
        LIMIT + 1);
  }

  private static Count count(int observed) {
    return new Count(Math.min(observed, LIMIT), observed > LIMIT);
  }
}
