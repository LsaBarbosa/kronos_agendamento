package com.appagendamento.exports.application;

import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.audit.api.AuditEvent;
import com.appagendamento.audit.api.AuditEventRecorder;
import com.appagendamento.outbox.api.OutboxEvent;
import com.appagendamento.outbox.api.OutboxEventRecorder;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConfirmExportAvailable {

  private final ExportWorkerRepository repository;
  private final AuditEventRecorder audit;
  private final OutboxEventRecorder outbox;
  private final AuditCorrelationId correlationId;

  public ConfirmExportAvailable(
      ExportWorkerRepository repository,
      AuditEventRecorder audit,
      OutboxEventRecorder outbox,
      AuditCorrelationId correlationId) {
    this.repository = repository;
    this.audit = audit;
    this.outbox = outbox;
    this.correlationId = correlationId;
  }

  @Transactional
  public Optional<ExportWorkerRepository.ExportTransition> execute(
      ExportWorkerRepository.ExportClaim claim,
      String objectKey,
      String encryptionKeyId,
      String artifactDigest,
      long artifactSize,
      Instant now) {
    var transition =
        repository.complete(claim, objectKey, encryptionKeyId, artifactDigest, artifactSize, now);
    transition.ifPresent(this::recordEffects);
    return transition;
  }

  private void recordEffects(ExportWorkerRepository.ExportTransition transition) {
    UUID trace = correlationId.currentTraceId();
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            transition.tenantId(),
            null,
            "SYSTEM",
            "EXPORT_LIFECYCLE",
            "EXPORT_AVAILABLE",
            "EXPORT",
            transition.exportId(),
            "COMMITTED",
            UUID.randomUUID(),
            trace,
            "{\"changed_fields\":[\"export\"],\"version_before\":"
                + transition.previousVersion()
                + ",\"version_after\":"
                + transition.version()
                + ",\"row_count\":"
                + transition.rowCount()
                + "}"));
    outbox.record(
        new OutboxEvent(
            UUID.randomUUID(),
            transition.tenantId(),
            "EXPORT",
            transition.exportId(),
            transition.version(),
            "ExportAvailable",
            1,
            transition.operationId(),
            trace,
            "{\"exportId\":\""
                + transition.exportId()
                + "\",\"version\":"
                + transition.version()
                + "}"));
  }
}
