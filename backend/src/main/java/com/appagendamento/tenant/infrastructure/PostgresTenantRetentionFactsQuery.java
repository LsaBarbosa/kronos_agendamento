package com.appagendamento.tenant.infrastructure;

import com.appagendamento.tenant.api.TenantRetentionFactsQuery;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresTenantRetentionFactsQuery implements TenantRetentionFactsQuery {

  private static final int QUERY_TIMEOUT_SECONDS = 2;
  private final JdbcTemplate jdbc;

  public PostgresTenantRetentionFactsQuery(JdbcTemplate jdbc) {
    this.jdbc = new JdbcTemplate(jdbc.getDataSource());
    this.jdbc.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
  }

  @Override
  public Facts query(UUID tenantId) {
    return jdbc.queryForObject(
        "SELECT status FROM agendamento.tenant WHERE tenant_id = ?",
        (row, index) -> new Facts(row.getString("status")),
        tenantId);
  }
}
