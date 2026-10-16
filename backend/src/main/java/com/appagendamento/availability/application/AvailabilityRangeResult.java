package com.appagendamento.availability.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record AvailabilityRangeResult(
    UUID serviceId,
    LocalDate from,
    LocalDate to,
    String timeZone,
    Instant generatedAt,
    boolean configured,
    List<AvailabilityDateResult> days) {

  public AvailabilityRangeResult {
    if (serviceId == null
        || from == null
        || to == null
        || timeZone == null
        || generatedAt == null
        || days == null) {
      throw new IllegalArgumentException("Resultado de disponibilidade incompleto");
    }
    days = List.copyOf(days);
  }
}
