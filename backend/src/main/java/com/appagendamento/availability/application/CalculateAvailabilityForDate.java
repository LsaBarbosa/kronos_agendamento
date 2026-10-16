package com.appagendamento.availability.application;

import com.appagendamento.availability.domain.AvailabilityCalculation;
import com.appagendamento.availability.domain.AvailabilityDaySchedule;
import com.appagendamento.availability.domain.AvailabilityPolicy;
import com.appagendamento.availability.domain.AvailabilityService;
import com.appagendamento.availability.domain.AvailabilitySlotCalculator;
import com.appagendamento.availability.domain.AvailabilityTemporalWindow;
import com.appagendamento.availability.domain.BlockedPeriodStatus;
import com.appagendamento.availability.domain.BoundaryInclusion;
import com.appagendamento.availability.domain.OccupiedInterval;
import com.appagendamento.availability.domain.ScheduleException;
import com.appagendamento.availability.domain.SlotGridAnchor;
import com.appagendamento.availability.domain.WeeklySchedule;
import com.appagendamento.catalog.api.ServiceAvailabilityQuery;
import com.appagendamento.catalog.api.ServiceAvailabilityView;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class CalculateAvailabilityForDate {

  private final ScheduleRepository schedules;
  private final ServiceAvailabilityQuery services;
  private final AppointmentOccupancyQuery appointments;
  private final Clock clock;

  public CalculateAvailabilityForDate(
      ScheduleRepository schedules,
      ServiceAvailabilityQuery services,
      AppointmentOccupancyQuery appointments,
      Clock clock) {
    this.schedules = schedules;
    this.services = services;
    this.appointments = appointments;
    this.clock = clock;
  }

  public AvailabilityDayResult execute(
      UUID tenantId,
      UUID serviceId,
      LocalDate date,
      SlotGridAnchor anchor,
      BoundaryInclusion noticeBoundary,
      BoundaryInclusion horizonBoundary) {
    if (tenantId == null
        || serviceId == null
        || date == null
        || anchor == null
        || noticeBoundary == null
        || horizonBoundary == null) {
      throw new IllegalArgumentException("Escopo e limites da consulta são obrigatórios");
    }
    ServiceAvailabilityView serviceView =
        services
            .findForAvailability(tenantId, serviceId)
            .orElseThrow(AvailabilityServiceNotFoundException::new);
    WeeklySchedule weekly = schedules.findWeeklySchedule(tenantId);
    ZoneId zone = ScheduleException.zone(weekly.timeZone());
    InstantRange dayRange = localDayRange(date, zone);
    List<ScheduleException> exceptions = schedules.findScheduleExceptions(tenantId, date, date);
    Optional<ScheduleException> exception =
        exceptions.stream().filter(value -> value.localDate().equals(date)).findFirst();
    AvailabilityDaySchedule effectiveDay =
        AvailabilityDaySchedule.resolve(tenantId, date, weekly, exception);
    Optional<AvailabilityPolicy> policy = schedules.findAvailabilityPolicy(tenantId);
    AvailabilityService service =
        new AvailabilityService(
            serviceView.serviceId(),
            serviceView.tenantId(),
            serviceView.active(),
            serviceView.durationMinutes(),
            serviceView.bufferAfterMinutes());
    List<OccupiedInterval> blocked =
        schedules.findBlockedPeriods(tenantId, dayRange.start(), dayRange.end()).stream()
            .filter(value -> value.status() == BlockedPeriodStatus.ACTIVE)
            .map(value -> new OccupiedInterval(value.startsAt(), value.endsAt()))
            .toList();
    List<OccupiedInterval> occupied =
        appointments.findOverlapping(tenantId, dayRange.start(), dayRange.end());
    List<OccupiedInterval> allOccupied =
        java.util.stream.Stream.concat(blocked.stream(), occupied.stream()).toList();
    Instant generatedAt = clock.instant();
    AvailabilityCalculation calculation;
    if (policy.isEmpty()) {
      calculation =
          AvailabilitySlotCalculator.calculate(
              effectiveDay, policy, service, zone, allOccupied, anchor);
    } else {
      Clock calculationClock = Clock.fixed(generatedAt, clock.getZone());
      AvailabilityTemporalWindow temporalWindow =
          AvailabilityTemporalWindow.from(
              calculationClock, zone, policy.get(), noticeBoundary, horizonBoundary);
      calculation =
          AvailabilitySlotCalculator.calculate(
              effectiveDay, policy, service, zone, allOccupied, anchor, temporalWindow);
    }
    return new AvailabilityDayResult(
        serviceId,
        date,
        weekly.timeZone(),
        generatedAt,
        calculation.ready(),
        calculation.explicitlyClosed(),
        calculation.slots());
  }

  private static InstantRange localDayRange(LocalDate date, ZoneId zone) {
    if (date == null) {
      throw new IllegalArgumentException("Data da consulta é obrigatória");
    }
    return new InstantRange(
        date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant());
  }

  private record InstantRange(Instant start, Instant end) {}
}
