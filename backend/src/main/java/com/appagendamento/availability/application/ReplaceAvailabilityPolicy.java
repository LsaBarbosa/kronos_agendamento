package com.appagendamento.availability.application;

import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.audit.api.AuditEventRecorder;
import com.appagendamento.availability.domain.AvailabilityPolicy;
import com.appagendamento.availability.domain.ScheduleAccessDeniedException;
import com.appagendamento.availability.domain.ScheduleVersionConflictException;
import com.appagendamento.idempotency.api.CommandResultCodec;
import com.appagendamento.idempotency.api.IdempotencyCoordinator;
import com.appagendamento.idempotency.api.IdempotentCommandResult;
import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.outbox.api.OutboxEvent;
import com.appagendamento.outbox.api.OutboxEventRecorder;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ReplaceAvailabilityPolicy {

  private final ScheduleRepository schedules;
  private final AdminAccessPolicy accessPolicy;
  private final IdempotencyCoordinator idempotency;
  private final AuditEventRecorder audit;
  private final AuditCorrelationId correlationId;
  private final OutboxEventRecorder outbox;
  private final CommandResultCodec<AvailabilityPolicy> resultCodec;

  public ReplaceAvailabilityPolicy(
      ScheduleRepository schedules,
      AdminAccessPolicy accessPolicy,
      IdempotencyCoordinator idempotency,
      AuditEventRecorder audit,
      AuditCorrelationId correlationId,
      OutboxEventRecorder outbox,
      CommandResultCodec<AvailabilityPolicy> resultCodec) {
    this.schedules = schedules;
    this.accessPolicy = accessPolicy;
    this.idempotency = idempotency;
    this.audit = audit;
    this.correlationId = correlationId;
    this.outbox = outbox;
    this.resultCodec = resultCodec;
  }

  public IdempotentCommandResult<AvailabilityPolicy> execute(
      AuthenticatedAdmin actor,
      String idempotencyKey,
      long expectedVersion,
      int slotStepMinutes,
      int minimumNoticeHours,
      int bookingHorizonDays,
      int cancellationCutoffHours,
      int reminderOffsetHours) {
    requireCurrent(actor);
    AvailabilityPolicy candidate =
        new AvailabilityPolicy(
            actor.tenantId(),
            slotStepMinutes,
            minimumNoticeHours,
            bookingHorizonDays,
            cancellationCutoffHours,
            reminderOffsetHours,
            1);
    String digest =
        AvailabilityPolicyCommandDigest.calculate(actor.tenantId(), candidate, expectedVersion);
    return idempotency.execute(
        actor.tenantId(),
        "availability.policy.replace.v1",
        idempotencyKey,
        digest,
        () -> requireCurrent(actor),
        operationId -> replace(actor, expectedVersion, candidate, operationId),
        resultCodec);
  }

  private AvailabilityPolicy replace(
      AuthenticatedAdmin actor,
      long expectedVersion,
      AvailabilityPolicy candidate,
      UUID operationId) {
    Optional<AvailabilityPolicy> current = schedules.findAvailabilityPolicy(actor.tenantId());
    long currentVersion = current.map(AvailabilityPolicy::version).orElse(0L);
    if (currentVersion != expectedVersion) {
      throw new ScheduleVersionConflictException();
    }
    if (current.filter(policy -> sameValues(policy, candidate)).isPresent()) {
      if (!schedules.lockAvailabilityPolicyVersion(actor.tenantId(), expectedVersion)) {
        throw new ScheduleVersionConflictException();
      }
      return current.orElseThrow();
    }
    if (!schedules.replaceAvailabilityPolicy(candidate, expectedVersion)) {
      throw new ScheduleVersionConflictException();
    }
    AvailabilityPolicy updated =
        schedules
            .findAvailabilityPolicy(actor.tenantId())
            .orElseThrow(() -> new IllegalStateException("Política atualizada não encontrada"));
    UUID traceId = correlationId.currentTraceId();
    audit.record(
        ReplaceWeeklySchedule.event(
            actor,
            "AVAILABILITY_POLICY_UPDATED",
            "availability_policy",
            currentVersion,
            updated.version(),
            traceId));
    outbox.record(
        new OutboxEvent(
            UUID.randomUUID(),
            actor.tenantId(),
            "AVAILABILITY_POLICY",
            actor.tenantId(),
            updated.version(),
            "AvailabilityPolicyChanged",
            1,
            operationId,
            traceId,
            "{\"tenantId\":\"" + actor.tenantId() + "\",\"version\":" + updated.version() + "}"));
    return updated;
  }

  private static boolean sameValues(AvailabilityPolicy left, AvailabilityPolicy right) {
    return left.slotStepMinutes() == right.slotStepMinutes()
        && left.minimumNoticeHours() == right.minimumNoticeHours()
        && left.bookingHorizonDays() == right.bookingHorizonDays()
        && left.cancellationCutoffHours() == right.cancellationCutoffHours()
        && left.reminderOffsetHours() == right.reminderOffsetHours();
  }

  private void requireCurrent(AuthenticatedAdmin actor) {
    if (!accessPolicy.isCurrent(actor)) {
      throw new ScheduleAccessDeniedException();
    }
  }
}
