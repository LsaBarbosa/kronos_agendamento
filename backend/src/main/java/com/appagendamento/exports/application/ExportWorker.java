package com.appagendamento.exports.application;

import com.appagendamento.exports.domain.ExportSnapshot;
import com.appagendamento.exports.domain.ExportUnavailableException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class ExportWorker {

  private static final Logger log = LoggerFactory.getLogger(ExportWorker.class);
  private static final Duration[] RETRIES = {
    Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(2)
  };
  private final ExportWorkerRepository repository;
  private final ExportConfiguration configuration;
  private final ExportCryptography cryptography;
  private final ExportArtifactStore artifactStore;
  private final ExportDocumentGenerator generator;
  private final ConfirmExportAvailable confirmer;
  private final ExpireExports expirer;
  private final Clock clock;
  private final ObjectMapper mapper;

  public ExportWorker(
      ExportWorkerRepository repository,
      ExportConfiguration configuration,
      ExportCryptography cryptography,
      ExportArtifactStore artifactStore,
      ExportDocumentGenerator generator,
      ConfirmExportAvailable confirmer,
      ExpireExports expirer,
      Clock clock,
      ObjectMapper mapper) {
    this.repository = repository;
    this.configuration = configuration;
    this.cryptography = cryptography;
    this.artifactStore = artifactStore;
    this.generator = generator;
    this.confirmer = confirmer;
    this.expirer = expirer;
    this.clock = clock;
    this.mapper = mapper;
  }

  public boolean processOne() {
    Instant now = clock.instant();
    var optional = repository.claim(now, configuration.leaseSeconds());
    if (optional.isEmpty()) return false;
    var claim = optional.get();
    byte[] encryptedArtifact;
    ExportCryptography.EncryptedData encrypted;
    try {
      if (!cryptography.available()) {
        repository.failFinal(claim, clock.instant(), "ENCRYPTION_UNAVAILABLE");
        return true;
      }
      if (!artifactStore.available()) {
        handleStorageFailure(claim, new ExportStorageException(true));
        return true;
      }
      if (claim.attemptCount() > RETRIES.length + 1) {
        repository.failFinal(claim, clock.instant(), "WORKER_LEASE_EXHAUSTED");
        return true;
      }
      byte[] snapshotBytes =
          cryptography.decrypt(
              claim.snapshotCiphertext(),
              claim.snapshotKeyId(),
              "EXPORT_SNAPSHOT",
              claim.tenantId().toString(),
              claim.exportId().toString());
      ExportSnapshot snapshot = mapper.readValue(snapshotBytes, ExportSnapshot.class);
      if (snapshot.rows().size() != claim.rowCount()
          || !sha256(snapshotBytes).equals(claim.snapshotDigest().trim())) {
        repository.failFinal(claim, clock.instant(), "SNAPSHOT_INVALID");
        return true;
      }
      byte[] generated = generator.generate(claim.format(), snapshot);
      encrypted =
          cryptography.encrypt(
              generated,
              "EXPORT_ARTIFACT",
              claim.tenantId().toString(),
              claim.exportId().toString());
      encryptedArtifact = encrypted.ciphertext();
    } catch (ExportUnavailableException failure) {
      repository.failFinal(claim, clock.instant(), "ENCRYPTION_UNAVAILABLE");
      return true;
    } catch (RuntimeException failure) {
      log.warn(
          "Export generation failed: exportId={}, code=GENERATION_FAILED, failureType={}",
          claim.exportId(),
          failure.getClass().getSimpleName());
      repository.failFinal(claim, clock.instant(), "GENERATION_FAILED");
      return true;
    }
    String objectKey = claim.tenantId() + "/exports/" + claim.exportId();
    try {
      artifactStore.put(objectKey, claim.version(), encryptedArtifact);
    } catch (ExportStorageException failure) {
      handleStorageFailure(claim, failure);
      return true;
    }
    String digest = sha256(encryptedArtifact);
    // Keep database confirmation failures visible. The job remains PROCESSING until its lease
    // expires, allowing a later fenced claim to safely persist the same logical artifact.
    confirmer.execute(
        claim, objectKey, encrypted.keyId(), digest, encryptedArtifact.length, clock.instant());
    return true;
  }

  public int expireAndClean() {
    Instant now = clock.instant();
    expirer.expireDue(now, 50);
    if (!artifactStore.available()) return 0;
    int deleted = 0;
    for (var target : repository.cleanupTargets(50)) {
      try {
        artifactStore.delete(target.objectKey(), target.version());
        if (repository.markArtifactDeleted(
            target.tenantId(), target.exportId(), target.version(), clock.instant())) {
          deleted++;
        }
      } catch (RuntimeException failure) {
        log.warn("Export cleanup failed: exportId={}", target.exportId());
      }
    }
    return deleted;
  }

  private void handleStorageFailure(
      ExportWorkerRepository.ExportClaim claim, ExportStorageException failure) {
    Instant now = clock.instant();
    if (!failure.retryable()) {
      repository.failFinal(claim, now, "STORAGE_REJECTED");
      return;
    }
    int attempt = claim.attemptCount();
    if (attempt > RETRIES.length) {
      repository.failFinal(claim, now, "STORAGE_TRANSIENT");
      return;
    }
    repository.retry(claim, now, now.plus(RETRIES[attempt - 1]), "STORAGE_TRANSIENT");
  }

  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
