package com.appagendamento.appointments.api;

import java.time.OffsetDateTime;

public record ValidatedAppointmentSlot(
    OffsetDateTime startsAt, OffsetDateTime serviceEndsAt, String timeZone) {

  public ValidatedAppointmentSlot {
    if (startsAt == null
        || serviceEndsAt == null
        || !serviceEndsAt.isAfter(startsAt)
        || timeZone == null
        || timeZone.isBlank()) {
      throw new IllegalArgumentException("Slot validado inválido");
    }
  }
}
