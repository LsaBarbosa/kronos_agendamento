package com.appagendamento.availability.application;

import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.audit.api.AuditEvent;
import com.appagendamento.audit.api.AuditEventRecorder;
import com.appagendamento.availability.domain.ScheduleAccessDeniedException;
import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.outbox.api.OutboxEvent;
import com.appagendamento.outbox.api.OutboxEventRecorder;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ScheduleMutationSupport {

  private final AdminAccessPolicy accessPolicy;
  private final AuditEventRecorder audit;
  private final AuditCorrelationId correlationId;
  private final OutboxEventRecorder outbox;
  private final ScheduleRepository schedules;

  public ScheduleMutationSupport(
      AdminAccessPolicy accessPolicy,
      AuditEventRecorder audit,
      AuditCorrelationId correlationId,
      OutboxEventRecorder outbox,
      ScheduleRepository schedules) {
    this.accessPolicy = accessPolicy;
    this.audit = audit;
    this.correlationId = correlationId;
    this.outbox = outbox;
    this.schedules = schedules;
  }

  public void requireCurrent(AuthenticatedAdmin actor) {
    if (!accessPolicy.isCurrent(actor)) {
      throw new ScheduleAccessDeniedException();
    }
  }

  @Transactional
  public void recordChange(
      AuthenticatedAdmin actor,
      UUID operationId,
      String action,
      String targetType,
      UUID targetId,
      String changedField,
      long versionBefore,
      long versionAfter) {
    UUID traceId = correlationId.currentTraceId();
    long scheduleRevision = schedules.nextScheduleRevision(actor.tenantId());
    String details =
        "{\"changed_fields\":[\""
            + changedField
            + "\"],\"version_before\":"
            + versionBefore
            + ",\"version_after\":"
            + versionAfter
            + "}";
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            actor.tenantId(),
            actor.adminUserId(),
            "ADMIN",
            "TENANT_ADMIN",
            action,
            targetType,
            targetId,
            "COMMITTED",
            traceId,
            traceId,
            details));
    outbox.record(
        new OutboxEvent(
            UUID.randomUUID(),
            actor.tenantId(),
            "SCHEDULE",
            actor.tenantId(),
            scheduleRevision,
            "ScheduleChanged",
            1,
            operationId,
            traceId,
            "{\"tenantId\":\"" + actor.tenantId() + "\",\"version\":" + scheduleRevision + "}"));
  }
}
