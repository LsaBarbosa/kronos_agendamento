package com.appagendamento.clients.infrastructure;

import com.appagendamento.clients.api.ClientRetentionFactsQuery;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresClientRetentionFactsQuery implements ClientRetentionFactsQuery {

  private static final int LIMIT = 10_000;
  private static final int QUERY_TIMEOUT_SECONDS = 2;
  private final JdbcTemplate jdbc;

  public PostgresClientRetentionFactsQuery(JdbcTemplate jdbc) {
    this.jdbc = new JdbcTemplate(jdbc.getDataSource());
    this.jdbc.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
  }

  @Override
  public Facts query(UUID tenantId) {
    return jdbc.queryForObject(
        """
        SELECT count(*) AS clients
        FROM (SELECT 1 FROM agendamento.client WHERE tenant_id = ? LIMIT ?) sampled
        """,
        (row, index) -> new Facts(count(row.getInt("clients"))),
        tenantId,
        LIMIT + 1);
  }

  private static Count count(int observed) {
    return new Count(Math.min(observed, LIMIT), observed > LIMIT);
  }
}
