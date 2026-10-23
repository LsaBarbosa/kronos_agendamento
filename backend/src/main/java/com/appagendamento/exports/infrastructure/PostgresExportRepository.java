package com.appagendamento.exports.infrastructure;

import com.appagendamento.exports.application.ExportRepository;
import com.appagendamento.exports.application.ExportWorkerRepository;
import com.appagendamento.exports.domain.ExportFilters;
import com.appagendamento.exports.domain.ExportFormat;
import com.appagendamento.exports.domain.ExportJobView;
import com.appagendamento.exports.domain.ExportState;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Repository
public class PostgresExportRepository implements ExportRepository, ExportWorkerRepository {

  private static final String JOB_COLUMNS =
      "export_id, operation_id, format, category, state, filters::text AS filters, snapshot_at, "
          + "attempt_count, next_attempt_at, available_at, expires_at, row_count, artifact_size, "
          + "artifact_digest, failure_code, version, created_at, updated_at";
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public PostgresExportRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  @Override
  public ExportJobView request(
      UUID exportId,
      UUID tenantId,
      UUID actorId,
      UUID operationId,
      ExportFormat format,
      ExportFilters filters,
      Instant snapshotAt,
      String snapshotKeyId,
      String snapshotDigest,
      byte[] encryptedSnapshot,
      int rowCount) {
    jdbc.queryForObject(
        "SELECT tenant_id FROM agendamento.tenant WHERE tenant_id = ? FOR UPDATE",
        UUID.class,
        tenantId);
    Boolean active =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM agendamento.export_job WHERE tenant_id = ? AND state IN ('REQUESTED','PROCESSING','RETRY_WAIT'))",
            Boolean.class,
            tenantId);
    if (Boolean.TRUE.equals(active)) {
      throw new com.appagendamento.exports.domain.ExportAlreadyActiveException();
    }
    jdbc.update(
        """
        INSERT INTO agendamento.export_job
          (export_id, tenant_id, requested_by_admin_id, operation_id, format, category,
           filters, snapshot_at, snapshot_ciphertext, snapshot_key_id, snapshot_digest,
           row_count, state, version, attempt_count, next_attempt_at, artifact_key)
        VALUES (?, ?, ?, ?, ?, 'APPOINTMENTS', ?::jsonb, ?, ?, ?, ?, ?, 'REQUESTED', 1, 0, ?, ?)
        """,
        exportId,
        tenantId,
        actorId,
        operationId,
        format.name(),
        mapper.writeValueAsString(filters),
        Timestamp.from(snapshotAt),
        encryptedSnapshot,
        snapshotKeyId,
        snapshotDigest,
        rowCount,
        Timestamp.from(snapshotAt),
        tenantId + "/exports/" + exportId);
    return find(tenantId, exportId).orElseThrow();
  }

  @Override
  public Optional<ExportJobView> find(UUID tenantId, UUID exportId) {
    return jdbc
        .query(
            "SELECT "
                + JOB_COLUMNS
                + " FROM agendamento.export_job WHERE tenant_id = ? AND export_id = ?",
            (row, index) -> mapJob(row),
            tenantId,
            exportId)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<ExportDownloadArtifact> authorizeDownload(
      UUID tenantId, UUID actorId, UUID exportId, String handleDigest, Instant now) {
    return jdbc
        .query(
            """
        SELECT job.export_id, job.artifact_key, job.artifact_key_id, job.artifact_digest,
               job.artifact_size, job.format, job.expires_at
        FROM agendamento.export_job job
        JOIN agendamento.export_download_handle handle
          ON handle.tenant_id = job.tenant_id AND handle.export_id = job.export_id
        WHERE job.tenant_id = ? AND job.export_id = ? AND handle.handle_digest = ?
          AND handle.issued_to_admin_id = ? AND handle.expires_at > ?
          AND job.state = 'AVAILABLE' AND job.expires_at > ?
        """,
            (row, index) ->
                new ExportDownloadArtifact(
                    row.getObject("export_id", UUID.class),
                    row.getString("artifact_key"),
                    row.getString("artifact_key_id"),
                    row.getString("artifact_digest"),
                    row.getLong("artifact_size"),
                    ExportFormat.valueOf(row.getString("format")),
                    row.getTimestamp("expires_at").toInstant()),
            tenantId,
            exportId,
            handleDigest,
            actorId,
            Timestamp.from(now),
            Timestamp.from(now))
        .stream()
        .findFirst();
  }

  @Override
  public Optional<Instant> issueHandle(
      UUID tenantId, UUID actorId, UUID exportId, String handleDigest, Instant now) {
    return jdbc
        .query(
            """
        INSERT INTO agendamento.export_download_handle
          (handle_digest, tenant_id, export_id, issued_to_admin_id, expires_at)
        SELECT ?, tenant_id, export_id, ?, expires_at
        FROM agendamento.export_job
        WHERE tenant_id = ? AND export_id = ? AND state = 'AVAILABLE' AND expires_at > ?
        RETURNING expires_at
        """,
            (row, index) -> row.getTimestamp("expires_at").toInstant(),
            handleDigest,
            actorId,
            tenantId,
            exportId,
            Timestamp.from(now))
        .stream()
        .findFirst();
  }

  @Override
  @Transactional
  public Optional<ExportClaim> claim(Instant now, long leaseSeconds) {
    List<UUID> candidates =
        jdbc.query(
            """
            SELECT export_id FROM agendamento.export_job
            WHERE (state IN ('REQUESTED','RETRY_WAIT') AND next_attempt_at <= ?)
               OR (state = 'PROCESSING' AND lease_until <= ?)
            ORDER BY next_attempt_at, created_at, export_id
            LIMIT 1 FOR UPDATE SKIP LOCKED
            """,
            (row, index) -> row.getObject("export_id", UUID.class),
            Timestamp.from(now),
            Timestamp.from(now));
    if (candidates.isEmpty()) return Optional.empty();
    UUID id = candidates.getFirst();
    UUID token = UUID.randomUUID();
    int claimed =
        jdbc.update(
            """
            UPDATE agendamento.export_job
            SET state = 'PROCESSING', version = version + 1, attempt_count = attempt_count + 1,
                claim_token = ?, lease_until = ?, failure_code = NULL, updated_at = ?
            WHERE export_id = ? AND ((state IN ('REQUESTED','RETRY_WAIT') AND next_attempt_at <= ?)
                OR (state = 'PROCESSING' AND lease_until <= ?))
            """,
            token,
            Timestamp.from(now.plusSeconds(leaseSeconds)),
            Timestamp.from(now),
            id,
            Timestamp.from(now),
            Timestamp.from(now));
    if (claimed != 1) return Optional.empty();
    return jdbc
        .query(
            """
        SELECT export_id, tenant_id, operation_id, requested_by_admin_id, format,
               attempt_count, version, claim_token, snapshot_key_id, snapshot_digest,
               snapshot_ciphertext, row_count
        FROM agendamento.export_job WHERE export_id = ? AND claim_token = ?
        """,
            (row, index) ->
                new ExportClaim(
                    row.getObject("export_id", UUID.class),
                    row.getObject("tenant_id", UUID.class),
                    row.getObject("operation_id", UUID.class),
                    row.getObject("requested_by_admin_id", UUID.class),
                    ExportFormat.valueOf(row.getString("format")),
                    row.getInt("attempt_count"),
                    row.getLong("version"),
                    row.getObject("claim_token", UUID.class),
                    row.getString("snapshot_key_id"),
                    row.getString("snapshot_digest"),
                    row.getBytes("snapshot_ciphertext"),
                    row.getInt("row_count")),
            id,
            token)
        .stream()
        .findFirst();
  }

  @Override
  @Transactional
  public Optional<ExportTransition> complete(
      ExportClaim claim,
      String objectKey,
      String encryptionKeyId,
      String artifactDigest,
      long artifactSize,
      Instant availableAt) {
    return jdbc
        .query(
            """
        UPDATE agendamento.export_job
        SET state = 'AVAILABLE', version = version + 1, claim_token = NULL, lease_until = NULL,
            artifact_key = ?, artifact_key_id = ?, artifact_digest = ?, artifact_size = ?,
            available_at = ?, expires_at = ?, failure_code = NULL,
            updated_at = ?
        WHERE tenant_id = ? AND export_id = ? AND operation_id = ? AND state = 'PROCESSING'
          AND version = ? AND claim_token = ? AND lease_until > ?
        RETURNING export_id, tenant_id, operation_id, requested_by_admin_id, version,
                  row_count, format
        """,
            (row, index) ->
                transition(row, "AVAILABLE", row.getLong("version") - 1, row.getLong("version")),
            objectKey,
            encryptionKeyId,
            artifactDigest,
            artifactSize,
            Timestamp.from(availableAt),
            Timestamp.from(availableAt.plus(Duration.ofMinutes(30))),
            Timestamp.from(availableAt),
            claim.tenantId(),
            claim.exportId(),
            claim.operationId(),
            claim.version(),
            claim.claimToken(),
            Timestamp.from(availableAt))
        .stream()
        .findFirst();
  }

  @Override
  public boolean retry(ExportClaim claim, Instant now, Instant nextAttemptAt, String failureCode) {
    return jdbc.update(
            """
            UPDATE agendamento.export_job
            SET state = 'RETRY_WAIT', version = version + 1, claim_token = NULL, lease_until = NULL,
                next_attempt_at = ?, failure_code = ?, updated_at = ?
            WHERE tenant_id = ? AND export_id = ? AND state = 'PROCESSING'
              AND version = ? AND claim_token = ? AND lease_until > ?
            """,
            Timestamp.from(nextAttemptAt),
            failureCode,
            Timestamp.from(now),
            claim.tenantId(),
            claim.exportId(),
            claim.version(),
            claim.claimToken(),
            Timestamp.from(now))
        == 1;
  }

  @Override
  public boolean failFinal(ExportClaim claim, Instant now, String failureCode) {
    return jdbc.update(
            """
            UPDATE agendamento.export_job
            SET state = 'FAILED_FINAL', version = version + 1, claim_token = NULL, lease_until = NULL,
                failure_code = ?, updated_at = ?
            WHERE tenant_id = ? AND export_id = ? AND state = 'PROCESSING'
              AND version = ? AND claim_token = ? AND lease_until > ?
            """,
            failureCode,
            Timestamp.from(now),
            claim.tenantId(),
            claim.exportId(),
            claim.version(),
            claim.claimToken(),
            Timestamp.from(now))
        == 1;
  }

  @Override
  @Transactional
  public List<ExportTransition> expireDue(Instant now, int limit) {
    List<UUID> ids =
        jdbc.query(
            """
            SELECT export_id FROM agendamento.export_job
            WHERE state = 'AVAILABLE' AND expires_at <= ?
            ORDER BY expires_at, export_id LIMIT ? FOR UPDATE SKIP LOCKED
            """,
            (row, index) -> row.getObject("export_id", UUID.class),
            Timestamp.from(now),
            limit);
    return ids.stream().map(id -> expireSystem(id, now)).flatMap(Optional::stream).toList();
  }

  private Optional<ExportTransition> expireSystem(UUID id, Instant now) {
    return jdbc
        .query(
            """
        UPDATE agendamento.export_job
        SET state = 'EXPIRED', version = version + 1, updated_at = ?
        WHERE export_id = ? AND state = 'AVAILABLE' AND expires_at <= ?
        RETURNING export_id, tenant_id, operation_id, requested_by_admin_id, version,
                  row_count, format
        """,
            (row, index) ->
                transition(row, "EXPIRED", row.getLong("version") - 1, row.getLong("version")),
            Timestamp.from(now),
            id,
            Timestamp.from(now))
        .stream()
        .findFirst();
  }

  @Override
  public List<CleanupTarget> cleanupTargets(int limit) {
    return jdbc.query(
        "SELECT tenant_id, export_id, artifact_key, version FROM agendamento.export_job "
            + "WHERE state IN ('EXPIRED','FAILED_FINAL') AND artifact_deleted_at IS NULL "
            + "ORDER BY updated_at, export_id LIMIT ?",
        (row, index) ->
            new CleanupTarget(
                row.getObject("tenant_id", UUID.class),
                row.getObject("export_id", UUID.class),
                row.getString("artifact_key"),
                row.getLong("version")),
        limit);
  }

  @Override
  public boolean markArtifactDeleted(
      UUID tenantId, UUID exportId, long expectedVersion, Instant now) {
    return jdbc.update(
            "UPDATE agendamento.export_job SET artifact_deleted_at = ?, updated_at = ? "
                + "WHERE tenant_id = ? AND export_id = ? AND state IN ('EXPIRED','FAILED_FINAL') "
                + "AND version = ? AND artifact_deleted_at IS NULL",
            Timestamp.from(now),
            Timestamp.from(now),
            tenantId,
            exportId,
            expectedVersion)
        == 1;
  }

  @Override
  @Transactional
  public boolean expireEarly(UUID tenantId, UUID exportId, long expectedVersion, Instant now) {
    return jdbc.update(
            "UPDATE agendamento.export_job SET state = 'EXPIRED', version = version + 1, updated_at = ? "
                + "WHERE tenant_id = ? AND export_id = ? AND state = 'AVAILABLE' AND version = ?",
            Timestamp.from(now),
            tenantId,
            exportId,
            expectedVersion)
        == 1;
  }

  private ExportJobView mapJob(java.sql.ResultSet row) throws java.sql.SQLException {
    ExportFilters filters = mapper.readValue(row.getString("filters"), ExportFilters.class);
    String state = row.getString("state");
    return new ExportJobView(
        row.getObject("export_id", UUID.class),
        row.getObject("operation_id", UUID.class),
        ExportFormat.valueOf(row.getString("format")),
        row.getString("category"),
        ExportState.valueOf(state),
        filters,
        instant(row, "snapshot_at"),
        Math.max(0, row.getInt("attempt_count") - 1),
        instant(row, "next_attempt_at"),
        instant(row, "available_at"),
        instant(row, "expires_at"),
        row.getInt("row_count"),
        nullableLong(row, "artifact_size"),
        row.getString("artifact_digest"),
        row.getString("failure_code"),
        row.getLong("version"),
        instant(row, "created_at"),
        instant(row, "updated_at"));
  }

  private static Instant instant(java.sql.ResultSet row, String column)
      throws java.sql.SQLException {
    Timestamp timestamp = row.getTimestamp(column);
    return timestamp == null ? null : timestamp.toInstant();
  }

  private static Long nullableLong(java.sql.ResultSet row, String column)
      throws java.sql.SQLException {
    long value = row.getLong(column);
    return row.wasNull() ? null : value;
  }

  private static ExportTransition transition(
      java.sql.ResultSet row, String state, long previousVersion, long version)
      throws java.sql.SQLException {
    return new ExportTransition(
        row.getObject("export_id", UUID.class),
        row.getObject("tenant_id", UUID.class),
        row.getObject("operation_id", UUID.class),
        row.getObject("requested_by_admin_id", UUID.class),
        previousVersion,
        version,
        row.getInt("row_count"),
        ExportFormat.valueOf(row.getString("format")),
        state);
  }
}
