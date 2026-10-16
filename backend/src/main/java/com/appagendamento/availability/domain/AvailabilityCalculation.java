package com.appagendamento.availability.domain;

import java.util.List;

public record AvailabilityCalculation(
    boolean ready, boolean explicitlyClosed, List<AvailabilitySlot> slots) {

  public AvailabilityCalculation {
    if (slots == null || (!ready && !slots.isEmpty()) || (explicitlyClosed && !slots.isEmpty())) {
      throw new ScheduleValidationException("Resultado do cálculo de disponibilidade inválido");
    }
    slots = List.copyOf(slots);
  }
}
