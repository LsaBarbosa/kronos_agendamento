package com.appagendamento.availability.application;

import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.audit.api.AuditEvent;
import com.appagendamento.audit.api.AuditEventRecorder;
import com.appagendamento.availability.domain.ScheduleAccessDeniedException;
import com.appagendamento.availability.domain.ScheduleVersionConflictException;
import com.appagendamento.availability.domain.WeeklyPeriod;
import com.appagendamento.availability.domain.WeeklySchedule;
import com.appagendamento.idempotency.api.CommandResultCodec;
import com.appagendamento.idempotency.api.IdempotencyCoordinator;
import com.appagendamento.idempotency.api.IdempotentCommandResult;
import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.outbox.api.OutboxEvent;
import com.appagendamento.outbox.api.OutboxEventRecorder;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ReplaceWeeklySchedule {

  private final ScheduleRepository schedules;
  private final AdminAccessPolicy accessPolicy;
  private final IdempotencyCoordinator idempotency;
  private final AuditEventRecorder audit;
  private final AuditCorrelationId correlationId;
  private final OutboxEventRecorder outbox;
  private final CommandResultCodec<WeeklySchedule> resultCodec;

  public ReplaceWeeklySchedule(
      ScheduleRepository schedules,
      AdminAccessPolicy accessPolicy,
      IdempotencyCoordinator idempotency,
      AuditEventRecorder audit,
      AuditCorrelationId correlationId,
      OutboxEventRecorder outbox,
      CommandResultCodec<WeeklySchedule> resultCodec) {
    this.schedules = schedules;
    this.accessPolicy = accessPolicy;
    this.idempotency = idempotency;
    this.audit = audit;
    this.correlationId = correlationId;
    this.outbox = outbox;
    this.resultCodec = resultCodec;
  }

  public IdempotentCommandResult<WeeklySchedule> execute(
      AuthenticatedAdmin actor,
      String idempotencyKey,
      long expectedVersion,
      List<WeeklyPeriod> periods) {
    requireCurrent(actor);
    WeeklySchedule current = schedules.findWeeklySchedule(actor.tenantId());
    List<WeeklyPeriod> canonical = WeeklySchedule.canonicalPeriods(periods);
    WeeklySchedule candidate =
        new WeeklySchedule(actor.tenantId(), current.timeZone(), true, canonical, 1);
    String digest =
        WeeklyScheduleCommandDigest.calculate(
            actor.tenantId(), current.timeZone(), expectedVersion, candidate.periods());
    return idempotency.execute(
        actor.tenantId(),
        "availability.weekly-schedule.replace.v1",
        idempotencyKey,
        digest,
        () -> requireCurrent(actor),
        operationId -> replace(actor, expectedVersion, candidate.periods(), operationId),
        resultCodec);
  }

  private WeeklySchedule replace(
      AuthenticatedAdmin actor,
      long expectedVersion,
      List<WeeklyPeriod> periods,
      UUID operationId) {
    WeeklySchedule current = schedules.findWeeklySchedule(actor.tenantId());
    if (current.version() != expectedVersion) {
      throw new ScheduleVersionConflictException();
    }
    if (current.configured() && current.periods().equals(periods)) {
      if (!schedules.lockWeeklyScheduleVersion(actor.tenantId(), expectedVersion)) {
        throw new ScheduleVersionConflictException();
      }
      return current;
    }
    if (!schedules.replaceWeeklySchedule(actor.tenantId(), expectedVersion, periods)) {
      throw new ScheduleVersionConflictException();
    }
    WeeklySchedule updated = schedules.findWeeklySchedule(actor.tenantId());
    UUID traceId = correlationId.currentTraceId();
    audit.record(
        event(
            actor,
            "SCHEDULE_UPDATED",
            "weekly_periods",
            current.version(),
            updated.version(),
            traceId));
    outbox.record(
        new OutboxEvent(
            UUID.randomUUID(),
            actor.tenantId(),
            "SCHEDULE",
            actor.tenantId(),
            updated.version(),
            "ScheduleChanged",
            1,
            operationId,
            traceId,
            "{\"tenantId\":\"" + actor.tenantId() + "\",\"version\":" + updated.version() + "}"));
    return updated;
  }

  static AuditEvent event(
      AuthenticatedAdmin actor,
      String action,
      String detailsField,
      long before,
      long after,
      UUID traceId) {
    return new AuditEvent(
        UUID.randomUUID(),
        actor.tenantId(),
        actor.adminUserId(),
        "ADMIN",
        "TENANT_ADMIN",
        action,
        "SCHEDULE_CONFIGURATION",
        actor.tenantId(),
        "COMMITTED",
        traceId,
        traceId,
        "{\"changed_fields\":[\""
            + detailsField
            + "\"],\"version_before\":"
            + before
            + ",\"version_after\":"
            + after
            + "}");
  }

  private void requireCurrent(AuthenticatedAdmin actor) {
    if (!accessPolicy.isCurrent(actor)) {
      throw new ScheduleAccessDeniedException();
    }
  }
}
