package com.appagendamento.exports.application;

import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.audit.api.AuditEvent;
import com.appagendamento.audit.api.AuditEventRecorder;
import com.appagendamento.exports.domain.ExportAuthorizationDeniedException;
import com.appagendamento.exports.domain.ExportConflictException;
import com.appagendamento.exports.domain.ExportExpiredException;
import com.appagendamento.exports.domain.ExportNotFoundException;
import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecordExportDownload {

  private final AdminAccessPolicy accessPolicy;
  private final ExportRepository repository;
  private final AuditEventRecorder audit;
  private final AuditCorrelationId correlationId;
  private final Clock clock;

  public RecordExportDownload(
      AdminAccessPolicy accessPolicy,
      ExportRepository repository,
      AuditEventRecorder audit,
      AuditCorrelationId correlationId,
      Clock clock) {
    this.accessPolicy = accessPolicy;
    this.repository = repository;
    this.audit = audit;
    this.correlationId = correlationId;
    this.clock = clock;
  }

  @Transactional
  public void execute(AuthenticatedAdmin actor, UUID exportId, String handleDigest) {
    if (actor == null || !accessPolicy.isCurrent(actor)) {
      throw new ExportAuthorizationDeniedException();
    }
    if (repository
        .authorizeDownload(
            actor.tenantId(), actor.adminUserId(), exportId, handleDigest, clock.instant())
        .isEmpty()) {
      var job =
          repository.find(actor.tenantId(), exportId).orElseThrow(ExportNotFoundException::new);
      if (job.state() == com.appagendamento.exports.domain.ExportState.EXPIRED
          || job.expiresAt() != null && !clock.instant().isBefore(job.expiresAt())) {
        throw new ExportExpiredException();
      }
      throw new ExportConflictException("DOWNLOAD_HANDLE_INVALID");
    }
    UUID traceId = correlationId.currentTraceId();
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            actor.tenantId(),
            actor.adminUserId(),
            "ADMIN",
            "TENANT_ADMIN",
            "EXPORT_DOWNLOADED",
            "EXPORT",
            exportId,
            "COMMITTED",
            UUID.randomUUID(),
            traceId,
            "{\"changed_fields\":[\"export_download\"]}"));
  }
}
