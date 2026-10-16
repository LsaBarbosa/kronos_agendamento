package com.appagendamento.availability.domain;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Objects;

public record AvailabilitySlot(Instant startsAt, Instant serviceEndsAt, Instant occupiedUntil) {

  public AvailabilitySlot {
    Objects.requireNonNull(startsAt);
    Objects.requireNonNull(serviceEndsAt);
    Objects.requireNonNull(occupiedUntil);
    if (!startsAt.isBefore(serviceEndsAt) || serviceEndsAt.isAfter(occupiedUntil)) {
      throw new ScheduleValidationException("Intervalo de disponibilidade inválido");
    }
  }

  public OffsetDateTime startsAt(ZoneId zone) {
    return startsAt.atZone(zone).toOffsetDateTime();
  }

  public OffsetDateTime serviceEndsAt(ZoneId zone) {
    return serviceEndsAt.atZone(zone).toOffsetDateTime();
  }

  public boolean overlaps(Instant otherStart, Instant otherEnd) {
    if (otherStart == null || otherEnd == null || !otherStart.isBefore(otherEnd)) {
      throw new ScheduleValidationException("Intervalo ocupado inválido");
    }
    return startsAt.isBefore(otherEnd) && otherStart.isBefore(occupiedUntil);
  }
}
