package com.appagendamento.availability.application;

import com.appagendamento.appointments.api.ConfirmedAppointmentConflictProbe;
import com.appagendamento.appointments.api.TenantAgendaLock;
import com.appagendamento.availability.domain.BlockedPeriod;
import com.appagendamento.availability.domain.BlockedPeriodStateConflictException;
import com.appagendamento.availability.domain.BlockedPeriodStatus;
import com.appagendamento.availability.domain.ConfirmedAppointmentConflictException;
import com.appagendamento.availability.domain.LocalTimeResolution;
import com.appagendamento.availability.domain.ScheduleResourceNotFoundException;
import com.appagendamento.availability.domain.ScheduleVersionConflictException;
import com.appagendamento.idempotency.api.CommandResultCodec;
import com.appagendamento.idempotency.api.IdempotencyCoordinator;
import com.appagendamento.idempotency.api.IdempotentCommandResult;
import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ManageBlockedPeriods {
  private final ScheduleRepository schedules;
  private final ScheduleMutationSupport support;
  private final AdminAccessPolicy accessPolicy;
  private final ConfirmedAppointmentConflictProbe conflicts;
  private final TenantAgendaLock agendaLock;
  private final IdempotencyCoordinator idempotency;
  private final CommandResultCodec<BlockedPeriod> codec;

  public ManageBlockedPeriods(
      ScheduleRepository schedules,
      ScheduleMutationSupport support,
      AdminAccessPolicy accessPolicy,
      ConfirmedAppointmentConflictProbe conflicts,
      TenantAgendaLock agendaLock,
      IdempotencyCoordinator idempotency,
      CommandResultCodec<BlockedPeriod> codec) {
    this.schedules = schedules;
    this.support = support;
    this.accessPolicy = accessPolicy;
    this.conflicts = conflicts;
    this.agendaLock = agendaLock;
    this.idempotency = idempotency;
    this.codec = codec;
  }

  public IdempotentCommandResult<BlockedPeriod> create(
      AuthenticatedAdmin actor, String key, String startsAt, String endsAt, String reason) {
    support.requireCurrent(actor);
    String zoneName = schedules.findTenantTimeZone(actor.tenantId());
    ZoneId zone = com.appagendamento.availability.domain.ScheduleException.zone(zoneName);
    LocalTimeResolution start = LocalTimeResolution.resolve(startsAt, zone);
    LocalTimeResolution end = LocalTimeResolution.resolve(endsAt, zone);
    String cleanReason = reason == null ? null : reason.trim();
    BlockedPeriod candidate =
        new BlockedPeriod(
            UUID.randomUUID(),
            actor.tenantId(),
            start.instant(),
            end.instant(),
            zoneName,
            cleanReason,
            BlockedPeriodStatus.ACTIVE,
            1,
            null,
            null);
    String scope = "availability.blocked-period.create.v1";
    String digest =
        BlockedPeriodCommandDigest.calculateCreate(
            actor.tenantId(), zoneName, start.instant(), end.instant(), cleanReason);
    return idempotency.execute(
        actor.tenantId(),
        scope,
        key,
        digest,
        () -> support.requireCurrent(actor),
        operationId -> {
          agendaLock.acquire(actor.tenantId());
          support.requireCurrent(actor);
          if (conflicts.overlapsConfirmed(actor.tenantId(), start.instant(), end.instant()))
            throw new ConfirmedAppointmentConflictException();
          BlockedPeriod stored =
              new BlockedPeriod(
                  operationId,
                  actor.tenantId(),
                  start.instant(),
                  end.instant(),
                  zoneName,
                  cleanReason,
                  BlockedPeriodStatus.ACTIVE,
                  1,
                  null,
                  null);
          schedules.insertBlockedPeriod(stored);
          support.recordChange(
              actor,
              operationId,
              "BLOCKED_PERIOD_CREATED",
              "BLOCKED_PERIOD",
              operationId,
              "blocked_period",
              0,
              1);
          return stored;
        },
        codec);
  }

  public IdempotentCommandResult<BlockedPeriod> cancel(
      AuthenticatedAdmin actor, String key, UUID blockId, long expectedVersion, String reason) {
    support.requireCurrent(actor);
    BlockedPeriod current =
        schedules
            .findBlockedPeriod(actor.tenantId(), blockId)
            .orElseThrow(ScheduleResourceNotFoundException::new);
    String normalizedReason = reason == null ? null : reason.trim();
    String scope = "availability.blocked-period.cancel.v1";
    String digest =
        BlockedPeriodCommandDigest.calculateCancel(
            actor.tenantId(), blockId, expectedVersion, normalizedReason);
    return idempotency.execute(
        actor.tenantId(),
        scope,
        key,
        digest,
        () -> support.requireCurrent(actor),
        operationId -> {
          if (current.version() != expectedVersion) throw new ScheduleVersionConflictException();
          if (current.status() != BlockedPeriodStatus.ACTIVE)
            throw new BlockedPeriodStateConflictException();
          BlockedPeriod cancelled = current.cancel(Instant.now(), normalizedReason);
          if (!schedules.cancelBlockedPeriod(cancelled, expectedVersion))
            throw new ScheduleVersionConflictException();
          support.recordChange(
              actor,
              operationId,
              "BLOCKED_PERIOD_CANCELLED",
              "BLOCKED_PERIOD",
              blockId,
              "blocked_period",
              expectedVersion,
              cancelled.version());
          return cancelled;
        },
        codec);
  }
}
