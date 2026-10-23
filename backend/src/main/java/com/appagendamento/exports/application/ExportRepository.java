package com.appagendamento.exports.application;

import com.appagendamento.exports.domain.ExportFilters;
import com.appagendamento.exports.domain.ExportFormat;
import com.appagendamento.exports.domain.ExportJobView;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ExportRepository {

  ExportJobView request(
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
      int rowCount);

  Optional<ExportJobView> find(UUID tenantId, UUID exportId);

  Optional<ExportDownloadArtifact> authorizeDownload(
      UUID tenantId, UUID actorId, UUID exportId, String handleDigest, Instant now);

  Optional<Instant> issueHandle(
      UUID tenantId, UUID actorId, UUID exportId, String handleDigest, Instant now);

  record ExportDownloadArtifact(
      UUID exportId,
      String artifactKey,
      String encryptionKeyId,
      String artifactDigest,
      long artifactSize,
      ExportFormat format,
      Instant expiresAt) {}
}
