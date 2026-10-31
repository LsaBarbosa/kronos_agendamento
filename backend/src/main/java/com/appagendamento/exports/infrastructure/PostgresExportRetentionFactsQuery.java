package com.appagendamento.exports.infrastructure;

import com.appagendamento.exports.api.ExportRetentionFactsQuery;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresExportRetentionFactsQuery implements ExportRetentionFactsQuery {

  private static final int LIMIT = 10_000;
  private static final int QUERY_TIMEOUT_SECONDS = 2;
  private final JdbcTemplate jdbc;

  public PostgresExportRetentionFactsQuery(JdbcTemplate jdbc) {
    this.jdbc = new JdbcTemplate(jdbc.getDataSource());
    this.jdbc.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
  }

  @Override
  public Facts query(UUID tenantId, Instant referenceAt) {
    return jdbc.queryForObject(
        """
        SELECT
          (SELECT count(*) FROM (SELECT 1 FROM agendamento.export_job
            WHERE tenant_id = ? LIMIT ?) sampled) AS jobs,
          (SELECT count(*) FROM (SELECT 1 FROM agendamento.export_job
            WHERE tenant_id = ? AND state IN ('REQUESTED','PROCESSING','RETRY_WAIT') LIMIT ?)
            sampled) AS active_jobs,
          (SELECT count(*) FROM (SELECT 1 FROM agendamento.export_job
            WHERE tenant_id = ? AND state = 'AVAILABLE' LIMIT ?) sampled) AS available_artifacts,
          (SELECT count(*) FROM (SELECT 1 FROM agendamento.export_job
            WHERE tenant_id = ? AND artifact_deleted_at IS NULL AND
              ((state IN ('EXPIRED','FAILED_FINAL')) OR
               (state = 'AVAILABLE' AND expires_at <= ?)) LIMIT ?) sampled) AS cleanup_pending
        """,
        (row, index) ->
            new Facts(
                count(row.getInt("jobs")),
                count(row.getInt("active_jobs")),
                count(row.getInt("available_artifacts")),
                count(row.getInt("cleanup_pending"))),
        tenantId,
        LIMIT + 1,
        tenantId,
        LIMIT + 1,
        tenantId,
        LIMIT + 1,
        tenantId,
        Timestamp.from(referenceAt),
        LIMIT + 1);
  }

  private static Count count(int observed) {
    return new Count(Math.min(observed, LIMIT), observed > LIMIT);
  }
}
