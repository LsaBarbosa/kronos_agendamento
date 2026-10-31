package com.appagendamento.subscriptions.infrastructure;

import com.appagendamento.subscriptions.api.BillingRetentionFactsQuery;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresBillingRetentionFactsQuery implements BillingRetentionFactsQuery {

  private static final int LIMIT = 10_000;
  private static final int QUERY_TIMEOUT_SECONDS = 2;
  private final JdbcTemplate jdbc;

  public PostgresBillingRetentionFactsQuery(JdbcTemplate jdbc) {
    this.jdbc = new JdbcTemplate(jdbc.getDataSource());
    this.jdbc.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
  }

  @Override
  public Facts query(UUID tenantId) {
    return jdbc.queryForObject(
        """
        SELECT
          (SELECT status FROM agendamento.subscription WHERE tenant_id = ? LIMIT 1)
            AS subscription_status,
          (SELECT count(*) FROM (SELECT 1 FROM agendamento.subscription
            WHERE tenant_id = ? LIMIT ?) sampled) AS subscriptions,
          (SELECT count(*) FROM (SELECT 1 FROM agendamento.billing_checkout_operation
            WHERE tenant_id = ? AND state IN ('REQUESTED','SUBMITTING','CHECKOUT_READY',
              'OUTCOME_UNKNOWN') LIMIT ?) sampled) AS open_checkouts,
          (SELECT count(*) FROM (SELECT 1 FROM agendamento.billing_checkout_operation
            WHERE tenant_id = ? AND state = 'OUTCOME_UNKNOWN' LIMIT ?) sampled) AS checkout_unknown,
          (SELECT count(*) FROM (SELECT 1 FROM agendamento.billing_dunning_attempt
            WHERE tenant_id = ? AND state IN ('SCHEDULED','BLOCKED_CONFIGURATION','CLAIMED',
              'SUBMITTING','RETRYABLE_NO_EFFECT','RECONCILING') LIMIT ?) sampled) AS dunning_pending,
          (SELECT count(*) FROM (SELECT 1 FROM agendamento.billing_dunning_attempt
            WHERE tenant_id = ? AND state = 'OUTCOME_UNKNOWN' LIMIT ?) sampled) AS dunning_unknown
        """,
        (row, index) ->
            new Facts(
                row.getString("subscription_status"),
                count(row.getInt("subscriptions")),
                count(row.getInt("open_checkouts")),
                count(row.getInt("checkout_unknown")),
                count(row.getInt("dunning_pending")),
                count(row.getInt("dunning_unknown"))),
        tenantId,
        tenantId,
        LIMIT + 1,
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
