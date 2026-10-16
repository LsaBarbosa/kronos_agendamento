package com.appagendamento.availability.application;

import com.appagendamento.availability.domain.ScheduleException;
import com.appagendamento.availability.domain.ScheduleExceptionAlreadyExistsException;
import com.appagendamento.availability.domain.ScheduleExceptionKind;
import com.appagendamento.availability.domain.ScheduleResourceNotFoundException;
import com.appagendamento.availability.domain.ScheduleVersionConflictException;
import com.appagendamento.availability.domain.SpecialPeriod;
import com.appagendamento.idempotency.api.CommandResultCodec;
import com.appagendamento.idempotency.api.IdempotencyCoordinator;
import com.appagendamento.idempotency.api.IdempotentCommandResult;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ManageScheduleExceptions {
  private final ScheduleRepository schedules;
  private final ScheduleMutationSupport support;
  private final IdempotencyCoordinator idempotency;
  private final CommandResultCodec<ScheduleException> codec;
  private final CommandResultCodec<ExceptionRemovalReceipt> removalCodec;

  public ManageScheduleExceptions(
      ScheduleRepository schedules,
      ScheduleMutationSupport support,
      IdempotencyCoordinator idempotency,
      CommandResultCodec<ScheduleException> codec,
      CommandResultCodec<ExceptionRemovalReceipt> removalCodec) {
    this.schedules = schedules;
    this.support = support;
    this.idempotency = idempotency;
    this.codec = codec;
    this.removalCodec = removalCodec;
  }

  public IdempotentCommandResult<ScheduleException> create(
      AuthenticatedAdmin actor,
      String key,
      LocalDate date,
      ScheduleExceptionKind kind,
      List<SpecialPeriod> periods,
      String reason) {
    support.requireCurrent(actor);
    String zone = schedules.findTenantTimeZone(actor.tenantId());
    ScheduleException candidate =
        candidate(UUID.randomUUID(), actor.tenantId(), date, kind, periods, reason, 1, zone);
    String scope = "availability.schedule-exception.create.v1";
    String digest =
        ScheduleExceptionCommandDigest.calculate(actor.tenantId(), scope, null, zone, 0, candidate);
    return idempotency.execute(
        actor.tenantId(),
        scope,
        key,
        digest,
        () -> support.requireCurrent(actor),
        operationId -> {
          ScheduleException stored =
              candidate(operationId, actor.tenantId(), date, kind, periods, reason, 1, zone);
          if (!schedules.insertScheduleException(stored))
            throw new ScheduleExceptionAlreadyExistsException();
          support.recordChange(
              actor,
              operationId,
              "SCHEDULE_EXCEPTION_CREATED",
              "SCHEDULE_EXCEPTION",
              operationId,
              "schedule_exception",
              0,
              1);
          return stored;
        },
        codec);
  }

  public IdempotentCommandResult<ScheduleException> update(
      AuthenticatedAdmin actor,
      String key,
      UUID id,
      long expectedVersion,
      LocalDate date,
      ScheduleExceptionKind kind,
      List<SpecialPeriod> periods,
      String reason) {
    support.requireCurrent(actor);
    String timeZone = schedules.findTenantTimeZone(actor.tenantId());
    ScheduleException candidate =
        candidate(id, actor.tenantId(), date, kind, periods, reason, expectedVersion + 1, timeZone);
    String scope = "availability.schedule-exception.update.v1";
    String digest =
        ScheduleExceptionCommandDigest.calculate(
            actor.tenantId(), scope, id, timeZone, expectedVersion, candidate);
    return idempotency.execute(
        actor.tenantId(),
        scope,
        key,
        digest,
        () -> support.requireCurrent(actor),
        operationId -> {
          ScheduleException latest =
              schedules
                  .findScheduleException(actor.tenantId(), id)
                  .orElseThrow(ScheduleResourceNotFoundException::new);
          if (latest.version() != expectedVersion) throw new ScheduleVersionConflictException();
          if (sameContent(latest, candidate)) {
            if (!schedules.lockScheduleExceptionVersion(actor.tenantId(), id, expectedVersion))
              throw new ScheduleVersionConflictException();
            return latest;
          }
          if (!schedules.updateScheduleException(candidate, expectedVersion))
            throw new ScheduleVersionConflictException();
          ScheduleException updated =
              schedules
                  .findScheduleException(actor.tenantId(), id)
                  .orElseThrow(ScheduleResourceNotFoundException::new);
          support.recordChange(
              actor,
              operationId,
              "SCHEDULE_EXCEPTION_UPDATED",
              "SCHEDULE_EXCEPTION",
              id,
              "schedule_exception",
              expectedVersion,
              updated.version());
          return updated;
        },
        codec);
  }

  public IdempotentCommandResult<ExceptionRemovalReceipt> remove(
      AuthenticatedAdmin actor, String key, UUID id, long expectedVersion) {
    support.requireCurrent(actor);
    String scope = "availability.schedule-exception.remove.v1";
    String digest =
        ScheduleExceptionCommandDigest.calculateRemoval(actor.tenantId(), id, expectedVersion);
    return idempotency.execute(
        actor.tenantId(),
        scope,
        key,
        digest,
        () -> support.requireCurrent(actor),
        operationId -> {
          schedules
              .findScheduleException(actor.tenantId(), id)
              .orElseThrow(ScheduleResourceNotFoundException::new);
          if (!schedules.deleteScheduleException(actor.tenantId(), id, expectedVersion))
            throw new ScheduleVersionConflictException();
          support.recordChange(
              actor,
              operationId,
              "SCHEDULE_EXCEPTION_REMOVED",
              "SCHEDULE_EXCEPTION",
              id,
              "schedule_exception",
              expectedVersion,
              expectedVersion + 1);
          return new ExceptionRemovalReceipt(id, true, expectedVersion + 1);
        },
        removalCodec);
  }

  private static ScheduleException candidate(
      UUID id,
      UUID tenant,
      LocalDate date,
      ScheduleExceptionKind kind,
      List<SpecialPeriod> periods,
      String reason,
      long version,
      String zone) {
    String normalizedReason = reason == null || reason.isBlank() ? null : reason.trim();
    return new ScheduleException(id, tenant, date, kind, periods, normalizedReason, version, zone);
  }

  private static boolean sameContent(ScheduleException left, ScheduleException right) {
    return left.localDate().equals(right.localDate())
        && left.kind() == right.kind()
        && left.periods().equals(right.periods())
        && java.util.Objects.equals(left.reason(), right.reason());
  }

  public record ExceptionRemovalReceipt(UUID exceptionId, boolean removed, long version) {}
}
