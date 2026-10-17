package com.appagendamento.availability.infrastructure;

import com.appagendamento.appointments.api.AdministrativeBookingAssessment;
import com.appagendamento.appointments.api.AdministrativeBookingPolicyQuery;
import com.appagendamento.appointments.api.AppointmentOccupancyQuery;
import com.appagendamento.appointments.domain.AdministrativeOverridePolicy;
import com.appagendamento.appointments.domain.AdministrativeScheduleConflictException;
import com.appagendamento.appointments.domain.AdministrativeScheduleVersionConflictException;
import com.appagendamento.appointments.domain.InvalidAppointmentTimeException;
import com.appagendamento.appointments.domain.SlotNoLongerAvailableException;
import com.appagendamento.availability.application.ScheduleRepository;
import com.appagendamento.availability.domain.AvailabilityDaySchedule;
import com.appagendamento.availability.domain.AvailabilityPolicy;
import com.appagendamento.availability.domain.AvailabilityService;
import com.appagendamento.availability.domain.AvailabilitySlot;
import com.appagendamento.availability.domain.AvailabilitySlotGenerator;
import com.appagendamento.availability.domain.BlockedPeriodStatus;
import com.appagendamento.availability.domain.ScheduleException;
import com.appagendamento.availability.domain.ScheduleExceptionKind;
import com.appagendamento.availability.domain.WeeklySchedule;
import com.appagendamento.catalog.api.ServiceAvailabilityQuery;
import com.appagendamento.catalog.api.ServiceAvailabilityView;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class AvailabilityAdministrativeBookingPolicyQuery
    implements AdministrativeBookingPolicyQuery {

  private final ScheduleRepository schedules;
  private final ServiceAvailabilityQuery services;
  private final AppointmentOccupancyQuery appointments;
  private final Clock clock;

  public AvailabilityAdministrativeBookingPolicyQuery(
      ScheduleRepository schedules,
      ServiceAvailabilityQuery services,
      AppointmentOccupancyQuery appointments,
      Clock clock) {
    this.schedules = schedules;
    this.services = services;
    this.appointments = appointments;
    this.clock = clock;
  }

  @Override
  public AdministrativeBookingAssessment assess(
      UUID tenantId,
      UUID serviceId,
      java.time.OffsetDateTime requestedStart,
      long expectedScheduleRevision,
      boolean allowOutsideWeeklySchedule,
      boolean allowMinimumNotice) {
    ServiceAvailabilityView service =
        services
            .findForBooking(tenantId, serviceId)
            .filter(
                value -> value.tenantId().equals(tenantId) && value.serviceId().equals(serviceId))
            .filter(ServiceAvailabilityView::active)
            .orElseThrow(AdministrativeScheduleConflictException::new);
    return assess(
        tenantId,
        serviceId,
        service.durationMinutes(),
        service.bufferAfterMinutes(),
        null,
        requestedStart,
        expectedScheduleRevision,
        allowOutsideWeeklySchedule,
        allowMinimumNotice);
  }

  @Override
  public AdministrativeBookingAssessment assessReschedule(
      UUID tenantId,
      UUID appointmentId,
      UUID serviceId,
      int durationMinutes,
      int bufferAfterMinutes,
      java.time.OffsetDateTime requestedStart,
      long expectedScheduleRevision,
      boolean allowOutsideWeeklySchedule,
      boolean allowMinimumNotice) {
    services
        .findForBooking(tenantId, serviceId)
        .filter(value -> value.tenantId().equals(tenantId) && value.serviceId().equals(serviceId))
        .filter(ServiceAvailabilityView::active)
        .orElseThrow(AdministrativeScheduleConflictException::new);
    return assess(
        tenantId,
        serviceId,
        durationMinutes,
        bufferAfterMinutes,
        appointmentId,
        requestedStart,
        expectedScheduleRevision,
        allowOutsideWeeklySchedule,
        allowMinimumNotice);
  }

  private AdministrativeBookingAssessment assess(
      UUID tenantId,
      UUID serviceId,
      int durationMinutes,
      int bufferAfterMinutes,
      UUID excludedAppointmentId,
      java.time.OffsetDateTime requestedStart,
      long expectedScheduleRevision,
      boolean allowOutsideWeeklySchedule,
      boolean allowMinimumNotice) {
    if (!schedules.lockScheduleRevision(tenantId, expectedScheduleRevision)) {
      throw new AdministrativeScheduleVersionConflictException();
    }
    WeeklySchedule weekly = schedules.findWeeklySchedule(tenantId);
    Optional<AvailabilityPolicy> policy = schedules.findAvailabilityPolicy(tenantId);
    if (!weekly.configured() || policy.isEmpty()) {
      throw new AdministrativeScheduleConflictException();
    }
    ZoneId zone = ZoneId.of(weekly.timeZone());
    if (!ZoneId.getAvailableZoneIds().contains(zone.getId())
        || !zone.getRules()
            .getValidOffsets(requestedStart.toLocalDateTime())
            .contains(requestedStart.getOffset())) {
      throw new InvalidAppointmentTimeException();
    }
    var capturedNow = clock.instant();
    AvailabilityPolicy configuredPolicy = policy.orElseThrow();
    LocalDate localDate = requestedStart.toLocalDate();
    LocalDate today = capturedNow.atZone(zone).toLocalDate();
    LocalDate lastEligible = today.plusDays(configuredPolicy.bookingHorizonDays() - 1L);
    if (localDate.isBefore(today) || localDate.isAfter(lastEligible)) {
      throw new AdministrativeScheduleConflictException();
    }

    ScheduleException exception =
        schedules.findScheduleExceptions(tenantId, localDate, localDate).stream()
            .findFirst()
            .orElse(null);
    if (exception != null && exception.kind() == ScheduleExceptionKind.CLOSED) {
      throw new AdministrativeScheduleConflictException();
    }
    AvailabilityDaySchedule day =
        AvailabilityDaySchedule.resolve(
            tenantId, localDate, weekly, Optional.ofNullable(exception));
    AvailabilityService availabilityService =
        new AvailabilityService(serviceId, tenantId, true, durationMinutes, bufferAfterMinutes);
    List<AvailabilitySlot> regularSlots = new ArrayList<>();
    if (!day.periods().isEmpty()) {
      for (var period : day.periods()) {
        regularSlots.addAll(
            AvailabilitySlotGenerator.generate(
                period.date(),
                period.startsAt(),
                period.endsAt(),
                period.startOffset(),
                period.endOffset(),
                zone,
                configuredPolicy.slotStepMinutes(),
                durationMinutes,
                bufferAfterMinutes));
      }
    }
    var matchingSlot =
        regularSlots.stream()
            .filter(slot -> slot.startsAt().equals(requestedStart.toInstant()))
            .filter(
                slot -> slot.startsAt().atZone(zone).getOffset().equals(requestedStart.getOffset()))
            .findFirst();
    boolean outsideSchedule = matchingSlot.isEmpty();
    if (outsideSchedule && exception != null && exception.kind() == ScheduleExceptionKind.SPECIAL) {
      throw new AdministrativeScheduleConflictException();
    }
    if (outsideSchedule && exception == null && !allowOutsideWeeklySchedule) {
      throw new AdministrativeScheduleConflictException();
    }

    var startsAt = requestedStart.toInstant();
    var serviceEndsAt = startsAt.plus(Duration.ofMinutes(availabilityService.durationMinutes()));
    var occupiedUntil =
        serviceEndsAt.plus(Duration.ofMinutes(availabilityService.bufferAfterMinutes()));
    var earliest = capturedNow.plus(Duration.ofHours(configuredPolicy.minimumNoticeHours()));
    boolean violatesNotice = startsAt.isBefore(earliest);
    if (violatesNotice && !allowMinimumNotice) {
      throw new AdministrativeScheduleConflictException();
    }
    var blocks = schedules.findBlockedPeriods(tenantId, startsAt, occupiedUntil);
    if (blocks.stream().anyMatch(block -> block.status() == BlockedPeriodStatus.ACTIVE)) {
      throw new SlotNoLongerAvailableException();
    }
    List<com.appagendamento.appointments.api.AppointmentOccupancy> occupancies =
        excludedAppointmentId == null
            ? appointments.findOverlapping(tenantId, startsAt, occupiedUntil)
            : appointments.findOverlappingExcluding(
                tenantId, startsAt, occupiedUntil, excludedAppointmentId);
    if (!occupancies.isEmpty()) {
      throw new SlotNoLongerAvailableException();
    }
    List<AdministrativeOverridePolicy> violated = new ArrayList<>();
    if (outsideSchedule) violated.add(AdministrativeOverridePolicy.OUTSIDE_WEEKLY_SCHEDULE);
    if (violatesNotice) violated.add(AdministrativeOverridePolicy.MIN_NOTICE);
    return new AdministrativeBookingAssessment(
        startsAt, serviceEndsAt, occupiedUntil, zone.getId(), violated);
  }
}
