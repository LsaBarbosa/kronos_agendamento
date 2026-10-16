package com.appagendamento.availability.domain;

import java.time.Instant;
import java.util.Objects;

public record OccupiedInterval(Instant startsAt, Instant endsAt) {

  public OccupiedInterval {
    Objects.requireNonNull(startsAt);
    Objects.requireNonNull(endsAt);
    if (!startsAt.isBefore(endsAt)) {
      throw new ScheduleValidationException("Intervalo ocupado inválido");
    }
  }

  public boolean overlaps(AvailabilitySlot slot) {
    return slot.startsAt().isBefore(endsAt) && startsAt.isBefore(slot.occupiedUntil());
  }
}
