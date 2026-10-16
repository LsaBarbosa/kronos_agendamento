package com.appagendamento.availability.domain;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class AvailabilitySlotCalculator {

  private AvailabilitySlotCalculator() {}

  public static AvailabilityCalculation calculate(
      AvailabilityDaySchedule day,
      Optional<AvailabilityPolicy> policy,
      AvailabilityService service,
      ZoneId zone,
      List<OccupiedInterval> blockedAndReserved,
      AvailabilityTemporalWindow temporalWindow) {
    Objects.requireNonNull(temporalWindow);
    AvailabilityCalculation result = calculate(day, policy, service, zone, blockedAndReserved);
    if (!result.ready() || result.slots().isEmpty()) {
      return result;
    }
    return new AvailabilityCalculation(
        true, result.explicitlyClosed(), filterByStartWindow(result.slots(), temporalWindow));
  }

  public static List<AvailabilitySlot> filterByStartWindow(
      List<AvailabilitySlot> slots, AvailabilityTemporalWindow window) {
    Objects.requireNonNull(slots);
    Objects.requireNonNull(window);
    return slots.stream().filter(slot -> window.includes(slot.startsAt())).toList();
  }

  public static AvailabilityCalculation calculate(
      AvailabilityDaySchedule day,
      Optional<AvailabilityPolicy> policy,
      AvailabilityService service,
      ZoneId zone,
      List<OccupiedInterval> blockedAndReserved) {
    Objects.requireNonNull(day);
    Objects.requireNonNull(policy);
    Objects.requireNonNull(service);
    Objects.requireNonNull(zone);
    Objects.requireNonNull(blockedAndReserved);
    if (!ZoneId.getAvailableZoneIds().contains(zone.getId())
        || !day.timeZone().equals(zone.getId())) {
      throw new ScheduleValidationException(
          "Fuso do cálculo deve ser o fuso IANA confiável do tenant");
    }
    if (!day.tenantId().equals(service.tenantId())
        || policy.isPresent() && !day.tenantId().equals(policy.get().tenantId())) {
      throw new ScheduleValidationException(
          "Fontes da disponibilidade pertencem a tenants diferentes");
    }
    if (!day.configured() || policy.isEmpty()) {
      return new AvailabilityCalculation(false, false, List.of());
    }
    if (day.explicitlyClosed() || !service.active()) {
      return new AvailabilityCalculation(true, day.explicitlyClosed(), List.of());
    }

    AvailabilityPolicy configuredPolicy = policy.get();
    List<AvailabilitySlot> candidates = new ArrayList<>();
    for (LocalAvailabilityPeriod period : day.periods()) {
      candidates.addAll(
          AvailabilitySlotGenerator.generate(
              period.date(),
              period.startsAt(),
              period.endsAt(),
              period.startOffset(),
              period.endOffset(),
              zone,
              configuredPolicy.slotStepMinutes(),
              service.durationMinutes(),
              service.bufferAfterMinutes()));
    }
    List<AvailabilitySlot> available =
        AvailabilitySlotGenerator.excludeOverlaps(candidates, blockedAndReserved).stream()
            .distinct()
            .sorted(java.util.Comparator.comparing(AvailabilitySlot::startsAt))
            .toList();
    return new AvailabilityCalculation(true, false, available);
  }
}
