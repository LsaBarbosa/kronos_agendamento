package com.appagendamento.exports.application;

import com.appagendamento.exports.domain.ExportFormat;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExportWorkerRepository {

  Optional<ExportClaim> claim(Instant now, long leaseSeconds);

  Optional<ExportTransition> complete(
      ExportClaim claim,
      String objectKey,
      String encryptionKeyId,
      String artifactDigest,
      long artifactSize,
      Instant availableAt);

  boolean retry(ExportClaim claim, Instant now, Instant nextAttemptAt, String failureCode);

  boolean failFinal(ExportClaim claim, Instant now, String failureCode);

  List<ExportTransition> expireDue(Instant now, int limit);

  List<CleanupTarget> cleanupTargets(int limit);

  boolean markArtifactDeleted(UUID tenantId, UUID exportId, long expectedVersion, Instant now);

  boolean expireEarly(UUID tenantId, UUID exportId, long expectedVersion, Instant now);

  record ExportClaim(
      UUID exportId,
      UUID tenantId,
      UUID operationId,
      UUID requestedByAdminId,
      ExportFormat format,
      int attemptCount,
      long version,
      UUID claimToken,
      String snapshotKeyId,
      String snapshotDigest,
      byte[] snapshotCiphertext,
      int rowCount) {
    public ExportClaim {
      snapshotCiphertext = snapshotCiphertext.clone();
    }

    @Override
    public byte[] snapshotCiphertext() {
      return snapshotCiphertext.clone();
    }
  }

  record ExportTransition(
      UUID exportId,
      UUID tenantId,
      UUID operationId,
      UUID actorAdminId,
      long previousVersion,
      long version,
      int rowCount,
      ExportFormat format,
      String state) {}

  record CleanupTarget(UUID tenantId, UUID exportId, String objectKey, long version) {}
}
