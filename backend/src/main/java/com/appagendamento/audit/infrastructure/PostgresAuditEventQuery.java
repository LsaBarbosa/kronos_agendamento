package com.appagendamento.audit.infrastructure;

import com.appagendamento.audit.api.AuditEventQuery;
import com.appagendamento.audit.api.AuditEventView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresAuditEventQuery implements AuditEventQuery {

  private final JdbcTemplate jdbc;

  public PostgresAuditEventQuery(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<AuditEventView> findForTenant(UUID authenticatedTenantId) {
    return jdbc.query(
        """
        SELECT event_id, tenant_id, actor_id, actor_type, actor_ref, action, target_type,
               target_id, result, occurred_at, recorded_at, correlation_id, trace_id,
               details::text AS details
        FROM agendamento.audit_event
        WHERE tenant_id = ?
        ORDER BY occurred_at DESC, event_id
        """,
        PostgresAuditEventQuery::map,
        authenticatedTenantId);
  }

  private static AuditEventView map(ResultSet row, int index) throws SQLException {
    return new AuditEventView(
        row.getObject("event_id", UUID.class),
        row.getObject("tenant_id", UUID.class),
        row.getObject("actor_id", UUID.class),
        row.getString("actor_type"),
        row.getString("actor_ref"),
        row.getString("action"),
        row.getString("target_type"),
        row.getObject("target_id", UUID.class),
        row.getString("result"),
        row.getTimestamp("occurred_at").toInstant(),
        row.getTimestamp("recorded_at").toInstant(),
        row.getObject("correlation_id", UUID.class),
        row.getObject("trace_id", UUID.class),
        row.getString("details"));
  }
}
