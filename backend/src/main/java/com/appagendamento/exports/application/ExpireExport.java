package com.appagendamento.exports.application;

import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.audit.api.AuditEvent;
import com.appagendamento.audit.api.AuditEventRecorder;
import com.appagendamento.exports.domain.ExportAuthorizationDeniedException;
import com.appagendamento.exports.domain.ExportConflictException;
import com.appagendamento.exports.domain.ExportJobView;
import com.appagendamento.exports.domain.ExportNotFoundException;
import com.appagendamento.exports.domain.ExportState;
import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.outbox.api.OutboxEvent;
import com.appagendamento.outbox.api.OutboxEventRecorder;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExpireExport {

  private final AdminAccessPolicy accessPolicy;
  private final ExportRepository exports;
  private final ExportWorkerRepository workerRepository;
  private final AuditEventRecorder audit;
  private final OutboxEventRecorder outbox;
  private final AuditCorrelationId correlationId;
  private final Clock clock;

  public ExpireExport(
      AdminAccessPolicy accessPolicy,
      ExportRepository exports,
      ExportWorkerRepository workerRepository,
      AuditEventRecorder audit,
      OutboxEventRecorder outbox,
      AuditCorrelationId correlationId,
      Clock clock) {
    this.accessPolicy = accessPolicy;
    this.exports = exports;
    this.workerRepository = workerRepository;
    this.audit = audit;
    this.outbox = outbox;
    this.correlationId = correlationId;
    this.clock = clock;
  }

  @Transactional
  public void execute(AuthenticatedAdmin actor, UUID exportId, long expectedVersion) {
    if (actor == null || !accessPolicy.isCurrent(actor)) {
      throw new ExportAuthorizationDeniedException();
    }
    ExportJobView job =
        exports.find(actor.tenantId(), exportId).orElseThrow(ExportNotFoundException::new);
    if (job.state() == ExportState.EXPIRED) return;
    if (job.state() != ExportState.AVAILABLE) {
      throw new ExportConflictException("EXPORT_NOT_EXPIRABLE");
    }
    if (job.version() != expectedVersion) {
      throw new ExportConflictException("VERSION_CONFLICT");
    }
    var now = clock.instant();
    if (!workerRepository.expireEarly(actor.tenantId(), exportId, expectedVersion, now)) {
      throw new ExportConflictException("VERSION_CONFLICT");
    }
    UUID trace = correlationId.currentTraceId();
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            actor.tenantId(),
            actor.adminUserId(),
            "ADMIN",
            "TENANT_ADMIN",
            "EXPORT_EXPIRED",
            "EXPORT",
            exportId,
            "COMMITTED",
            UUID.randomUUID(),
            trace,
            "{\"changed_fields\":[\"export\"],\"version_before\":"
                + expectedVersion
                + ",\"version_after\":"
                + (expectedVersion + 1)
                + "}"));
    outbox.record(
        new OutboxEvent(
            UUID.randomUUID(),
            actor.tenantId(),
            "EXPORT",
            exportId,
            expectedVersion + 1,
            "ExportExpired",
            1,
            job.operationId(),
            trace,
            "{\"exportId\":\"" + exportId + "\",\"version\":" + (expectedVersion + 1) + "}"));
  }
}
