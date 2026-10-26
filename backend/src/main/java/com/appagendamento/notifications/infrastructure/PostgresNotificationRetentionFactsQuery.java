package com.appagendamento.notifications.infrastructure;

import com.appagendamento.notifications.api.NotificationRetentionFactsQuery;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresNotificationRetentionFactsQuery implements NotificationRetentionFactsQuery {

  private static final int LIMIT = 10_000;
  private static final int QUERY_TIMEOUT_SECONDS = 2;
  private final JdbcTemplate jdbc;

  public PostgresNotificationRetentionFactsQuery(JdbcTemplate jdbc) {
    this.jdbc = new JdbcTemplate(jdbc.getDataSource());
    this.jdbc.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
  }

  @Override
  public Facts query(UUID tenantId) {
    return jdbc.queryForObject(
        """
        SELECT
          (SELECT count(*) FROM (SELECT 1 FROM agendamento.notification_intent
            WHERE tenant_id = ? LIMIT ?) sampled) AS intents,
          (SELECT count(*) FROM (SELECT 1 FROM agendamento.notification_intent
            WHERE tenant_id = ? AND state IN ('PENDING','CLAIMED','RETRY') LIMIT ?) sampled)
            AS pending,
          (SELECT count(*) FROM (SELECT 1 FROM agendamento.notification_intent
            WHERE tenant_id = ? AND state = 'OUTCOME_UNKNOWN' LIMIT ?) sampled) AS unknown_count,
          (SELECT count(*) FROM (SELECT 1 FROM agendamento.notification_intent
            WHERE tenant_id = ? AND state = 'ACCEPTED' LIMIT ?) sampled) AS accepted_count
        """,
        (row, index) ->
            new Facts(
                count(row.getInt("intents")),
                count(row.getInt("pending")),
                count(row.getInt("unknown_count")),
                count(row.getInt("accepted_count"))),
        tenantId,
        LIMIT + 1,
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
