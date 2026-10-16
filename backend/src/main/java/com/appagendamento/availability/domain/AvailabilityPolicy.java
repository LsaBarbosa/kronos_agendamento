package com.appagendamento.availability.domain;

import java.util.Set;
import java.util.UUID;

public record AvailabilityPolicy(
    UUID tenantId,
    int slotStepMinutes,
    int minimumNoticeHours,
    int bookingHorizonDays,
    int cancellationCutoffHours,
    int reminderOffsetHours,
    long version) {

  private static final Set<Integer> SLOT_STEPS = Set.of(15, 30, 60);
  private static final Set<Integer> HORIZONS = Set.of(7, 30, 60, 90);
  private static final Set<Integer> REMINDER_OFFSETS = Set.of(2, 6, 12, 24);

  public AvailabilityPolicy {
    if (tenantId == null
        || !SLOT_STEPS.contains(slotStepMinutes)
        || minimumNoticeHours < 1
        || minimumNoticeHours > 24
        || !HORIZONS.contains(bookingHorizonDays)
        || cancellationCutoffHours < 2
        || cancellationCutoffHours > 24
        || !REMINDER_OFFSETS.contains(reminderOffsetHours)
        || version < 0) {
      throw new ScheduleValidationException(
          "Política de disponibilidade fora das opções permitidas");
    }
  }
}
