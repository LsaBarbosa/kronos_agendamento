package com.appagendamento.availability.application;

import com.appagendamento.availability.domain.AvailabilitySlot;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

public record AvailabilityDayResult(
    UUID serviceId,
    LocalDate date,
    String timeZone,
    Instant generatedAt,
    boolean configured,
    boolean explicitlyClosed,
    List<AvailabilitySlot> slots) {

  public AvailabilityDayResult {
    if (serviceId == null
        || date == null
        || timeZone == null
        || generatedAt == null
        || slots == null) {
      throw new IllegalArgumentException("Resultado diário de disponibilidade incompleto");
    }
    slots = List.copyOf(slots);
  }

  public List<OffsetAvailabilitySlot> offsetAwareSlots() {
    ZoneId zone = ZoneId.of(timeZone);
    return slots.stream()
        .map(slot -> new OffsetAvailabilitySlot(slot.startsAt(zone), slot.serviceEndsAt(zone)))
        .toList();
  }

  public record OffsetAvailabilitySlot(OffsetDateTime startsAt, OffsetDateTime serviceEndsAt) {}
}
