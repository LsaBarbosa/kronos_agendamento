package com.appagendamento.exports.application;

import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.audit.api.AuditEvent;
import com.appagendamento.audit.api.AuditEventRecorder;
import com.appagendamento.outbox.api.OutboxEvent;
import com.appagendamento.outbox.api.OutboxEventRecorder;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExpireExports {

  private final ExportWorkerRepository repository;
  private final AuditEventRecorder audit;
  private final OutboxEventRecorder outbox;
  private final AuditCorrelationId correlationId;

  public ExpireExports(
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
  public List<ExportWorkerRepository.ExportTransition> expireDue(Instant now, int limit) {
    var transitions = repository.expireDue(now, limit);
    for (var transition : transitions) record(transition);
    return transitions;
  }

  private void record(ExportWorkerRepository.ExportTransition transition) {
    UUID trace = correlationId.currentTraceId();
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            transition.tenantId(),
            null,
            "SYSTEM",
            "EXPORT_LIFECYCLE",
            "EXPORT_EXPIRED",
            "EXPORT",
            transition.exportId(),
            "COMMITTED",
            UUID.randomUUID(),
            trace,
            "{\"changed_fields\":[\"export\"],\"version_before\":"
                + transition.previousVersion()
                + ",\"version_after\":"
                + transition.version()
                + "}"));
    outbox.record(
        new OutboxEvent(
            UUID.randomUUID(),
            transition.tenantId(),
            "EXPORT",
            transition.exportId(),
            transition.version(),
            "ExportExpired",
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
