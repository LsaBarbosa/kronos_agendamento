package com.appagendamento.availability.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record AvailabilityDateResult(
    LocalDate date, boolean explicitlyClosed, List<AvailabilitySlotView> slots) {

  public AvailabilityDateResult {
    if (date == null || slots == null) {
      throw new IllegalArgumentException("Resultado diário incompleto");
    }
    slots = List.copyOf(slots);
  }

  public record AvailabilitySlotView(OffsetDateTime startsAt, OffsetDateTime serviceEndsAt) {}
}
