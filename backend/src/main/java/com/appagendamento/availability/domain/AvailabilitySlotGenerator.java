package com.appagendamento.availability.domain;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class AvailabilitySlotGenerator {

  private AvailabilitySlotGenerator() {}

  public static List<AvailabilitySlot> generate(
      LocalDate date,
      LocalTime localStart,
      LocalTime localEnd,
      ZoneOffset explicitStartOffset,
      ZoneOffset explicitEndOffset,
      ZoneId zone,
      int slotStepMinutes,
      int serviceDurationMinutes,
      int bufferAfterMinutes) {
    Objects.requireNonNull(date);
    Objects.requireNonNull(localStart);
    Objects.requireNonNull(localEnd);
    Objects.requireNonNull(zone);
    if (!ZoneId.getAvailableZoneIds().contains(zone.getId())) {
      throw new ScheduleValidationException("O fuso deve ser uma zona IANA");
    }
    if (!localStart.isBefore(localEnd)
        || localStart.getSecond() != 0
        || localStart.getNano() != 0
        || localEnd.getSecond() != 0
        || localEnd.getNano() != 0
        || localStart.getMinute() % 15 != 0
        || localEnd.getMinute() % 15 != 0) {
      throw new ScheduleValidationException("Período de disponibilidade inválido");
    }
    if (slotStepMinutes != 15 && slotStepMinutes != 30 && slotStepMinutes != 60) {
      throw new ScheduleValidationException("Granularidade de slot inválida");
    }
    if (serviceDurationMinutes <= 0
        || serviceDurationMinutes % 15 != 0
        || bufferAfterMinutes < 0
        || bufferAfterMinutes % 15 != 0) {
      throw new ScheduleValidationException("Duração ou buffer do serviço inválido");
    }

    LocalDateTime localPeriodStart = date.atTime(localStart);
    LocalDateTime localPeriodEnd = date.atTime(localEnd);
    ZoneOffset startOffset = resolvePeriodOffset(localPeriodStart, zone, explicitStartOffset);
    ZoneOffset endOffset = resolvePeriodOffset(localPeriodEnd, zone, explicitEndOffset);
    if (startOffset == null || endOffset == null) {
      return List.of();
    }
    var periodStart = localPeriodStart.toInstant(startOffset);
    var periodEnd = localPeriodEnd.toInstant(endOffset);
    if (!periodStart.isBefore(periodEnd)) {
      return List.of();
    }

    int firstMinute = localStart.getHour() * 60 + localStart.getMinute();
    int step = slotStepMinutes;
    Duration serviceDuration = Duration.ofMinutes(serviceDurationMinutes);
    Duration buffer = Duration.ofMinutes(bufferAfterMinutes);
    List<AvailabilitySlot> result = new ArrayList<>();
    for (int minuteOfDay = firstMinute;
        minuteOfDay < localEnd.getHour() * 60 + localEnd.getMinute();
        minuteOfDay += step) {
      LocalTime candidateTime = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60);
      LocalDateTime candidateLocal = date.atTime(candidateTime);
      List<ZoneOffset> validOffsets = zone.getRules().getValidOffsets(candidateLocal);
      if (validOffsets.size() != 1) {
        continue;
      }
      var startsAt = candidateLocal.toInstant(validOffsets.getFirst());
      var serviceEndsAt = startsAt.plus(serviceDuration);
      var occupiedUntil = serviceEndsAt.plus(buffer);
      if (!startsAt.isBefore(periodStart) && !occupiedUntil.isAfter(periodEnd)) {
        result.add(new AvailabilitySlot(startsAt, serviceEndsAt, occupiedUntil));
      }
    }
    return List.copyOf(result);
  }

  public static List<AvailabilitySlot> excludeOverlaps(
      List<AvailabilitySlot> candidates, List<OccupiedInterval> occupiedIntervals) {
    Objects.requireNonNull(candidates);
    Objects.requireNonNull(occupiedIntervals);
    if (candidates.stream().anyMatch(Objects::isNull)
        || occupiedIntervals.stream().anyMatch(Objects::isNull)) {
      throw new ScheduleValidationException("Candidatos e intervalos ocupados são obrigatórios");
    }
    return candidates.stream()
        .filter(
            candidate ->
                occupiedIntervals.stream().noneMatch(interval -> interval.overlaps(candidate)))
        .toList();
  }

  private static ZoneOffset resolvePeriodOffset(
      LocalDateTime localDateTime, ZoneId zone, ZoneOffset explicitOffset) {
    List<ZoneOffset> offsets = zone.getRules().getValidOffsets(localDateTime);
    if (explicitOffset != null) {
      if (!offsets.contains(explicitOffset)) {
        throw new InvalidLocalTimeException("Offset do período não é válido para o fuso");
      }
      return explicitOffset;
    }
    return offsets.size() == 1 ? offsets.getFirst() : null;
  }
}
