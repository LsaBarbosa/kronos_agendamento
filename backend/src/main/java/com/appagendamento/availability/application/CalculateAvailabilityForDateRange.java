package com.appagendamento.availability.application;

import com.appagendamento.availability.domain.AvailabilityCalculation;
import com.appagendamento.availability.domain.AvailabilityDaySchedule;
import com.appagendamento.availability.domain.AvailabilityPolicy;
import com.appagendamento.availability.domain.AvailabilityService;
import com.appagendamento.availability.domain.AvailabilitySlotCalculator;
import com.appagendamento.availability.domain.AvailabilityTemporalWindow;
import com.appagendamento.availability.domain.BlockedPeriodStatus;
import com.appagendamento.availability.domain.OccupiedInterval;
import com.appagendamento.availability.domain.ScheduleException;
import com.appagendamento.availability.domain.ScheduleExceptionKind;
import com.appagendamento.availability.domain.WeeklySchedule;
import com.appagendamento.catalog.api.ServiceAvailabilityQuery;
import com.appagendamento.catalog.api.ServiceAvailabilityView;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CalculateAvailabilityForDateRange {

  public static final int MAX_QUERY_DATES = 31;

  private final AvailabilityReadTransaction readTransaction;
  private final ScheduleRepository schedules;
  private final ServiceAvailabilityQuery services;
  private final AppointmentOccupancyQuery appointments;
  private final Clock clock;

  public CalculateAvailabilityForDateRange(
      AvailabilityReadTransaction readTransaction,
      ScheduleRepository schedules,
      ServiceAvailabilityQuery services,
      AppointmentOccupancyQuery appointments,
      Clock clock) {
    this.readTransaction = readTransaction;
    this.schedules = schedules;
    this.services = services;
    this.appointments = appointments;
    this.clock = clock;
  }

  public AvailabilityRangeResult execute(
      UUID tenantId, UUID serviceId, LocalDate from, LocalDate to) {
    validate(tenantId, serviceId, from, to);
    return readTransaction.execute(() -> calculate(tenantId, serviceId, from, to));
  }

  public AvailabilityRangeResult executeForAuthorizedTenant(
      Supplier<UUID> tenantResolver, UUID serviceId, LocalDate from, LocalDate to) {
    if (tenantResolver == null
        || serviceId == null
        || from == null
        || to == null
        || from.isAfter(to)) {
      throw new InvalidAvailabilityWindowException();
    }
    long dates = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
    if (dates > MAX_QUERY_DATES) {
      throw new InvalidAvailabilityWindowException();
    }
    return readTransaction.execute(
        () -> {
          UUID tenantId = tenantResolver.get();
          validate(tenantId, serviceId, from, to);
          return calculate(tenantId, serviceId, from, to);
        });
  }

  private AvailabilityRangeResult calculate(
      UUID tenantId, UUID serviceId, LocalDate from, LocalDate to) {
    Instant generatedAt = clock.instant();
    ServiceAvailabilityView serviceView =
        services
            .findForAvailability(tenantId, serviceId)
            .orElseThrow(AvailabilityServiceNotFoundException::new);
    WeeklySchedule weekly = schedules.findWeeklySchedule(tenantId);
    if (!serviceView.tenantId().equals(tenantId)
        || !serviceView.serviceId().equals(serviceId)
        || !weekly.tenantId().equals(tenantId)) {
      throw new AvailabilityServiceNotFoundException();
    }
    if (!serviceView.active()) {
      throw new AvailabilityServiceNotFoundException();
    }
    ZoneId zone = ScheduleException.zone(weekly.timeZone());
    Optional<AvailabilityPolicy> policy = schedules.findAvailabilityPolicy(tenantId);
    if (policy.isPresent() && !policy.get().tenantId().equals(tenantId)) {
      throw new AvailabilityServiceNotFoundException();
    }
    AvailabilityService service =
        new AvailabilityService(
            serviceView.serviceId(),
            serviceView.tenantId(),
            serviceView.active(),
            serviceView.durationMinutes(),
            serviceView.bufferAfterMinutes());

    LocalDate today = generatedAt.atZone(zone).toLocalDate();
    LocalDate fromEligible = from.isAfter(today) ? from : today;
    LocalDate lastEligible =
        policy
            .map(value -> today.plusDays(value.bookingHorizonDays() - 1L))
            .orElse(today.minusDays(1));
    LocalDate toEligible = to.isBefore(lastEligible) ? to : lastEligible;
    boolean hasEligibleDates = !fromEligible.isAfter(toEligible);
    AvailabilityTemporalWindow temporalWindow =
        policy.map(value -> AvailabilityTemporalWindow.from(generatedAt, zone, value)).orElse(null);

    List<ScheduleException> exceptions = schedules.findScheduleExceptions(tenantId, from, to);
    List<OccupiedInterval> allOccupied = List.of();
    if (hasEligibleDates && weekly.configured() && policy.isPresent() && service.active()) {
      Instant rangeStart = fromEligible.atStartOfDay(zone).toInstant();
      Instant rangeEnd = toEligible.plusDays(1).atStartOfDay(zone).toInstant();
      List<OccupiedInterval> blocked =
          schedules.findBlockedPeriods(tenantId, rangeStart, rangeEnd).stream()
              .filter(value -> value.status() == BlockedPeriodStatus.ACTIVE)
              .map(value -> new OccupiedInterval(value.startsAt(), value.endsAt()))
              .toList();
      List<OccupiedInterval> occupied =
          appointments.findOverlapping(tenantId, rangeStart, rangeEnd);
      allOccupied = Stream.concat(blocked.stream(), occupied.stream()).toList();
    }
    Map<LocalDate, ScheduleException> exceptionsByDate =
        exceptions.stream()
            .collect(
                Collectors.toUnmodifiableMap(ScheduleException::localDate, Function.identity()));

    List<AvailabilityDateResult> days = new ArrayList<>();
    long requestedDates = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
    for (long offset = 0; offset < requestedDates; offset++) {
      LocalDate date = from.plusDays(offset);
      List<AvailabilityDateResult.AvailabilitySlotView> slots = List.of();
      ScheduleException exception = exceptionsByDate.get(date);
      boolean explicitlyClosed =
          exception != null && exception.kind() == ScheduleExceptionKind.CLOSED;
      if (hasEligibleDates
          && !date.isBefore(fromEligible)
          && !date.isAfter(toEligible)
          && weekly.configured()
          && policy.isPresent()
          && service.active()) {
        AvailabilityDaySchedule day =
            AvailabilityDaySchedule.resolve(tenantId, date, weekly, Optional.ofNullable(exception));
        explicitlyClosed = day.explicitlyClosed();
        AvailabilityCalculation calculation =
            AvailabilitySlotCalculator.calculate(
                day, policy, service, zone, allOccupied, temporalWindow);
        slots =
            calculation.slots().stream()
                .map(
                    slot ->
                        new AvailabilityDateResult.AvailabilitySlotView(
                            slot.startsAt(zone), slot.serviceEndsAt(zone)))
                .toList();
      }
      days.add(new AvailabilityDateResult(date, explicitlyClosed, slots));
    }
    return new AvailabilityRangeResult(
        serviceId,
        from,
        to,
        zone.getId(),
        generatedAt,
        weekly.configured() && policy.isPresent(),
        days);
  }

  private static void validate(UUID tenantId, UUID serviceId, LocalDate from, LocalDate to) {
    if (tenantId == null || serviceId == null || from == null || to == null || from.isAfter(to)) {
      throw new InvalidAvailabilityWindowException();
    }
    long dates = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
    if (dates > MAX_QUERY_DATES) {
      throw new InvalidAvailabilityWindowException();
    }
  }
}
