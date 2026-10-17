package com.appagendamento.appointments.api;

import java.time.Instant;

public record AppointmentOccupancy(Instant startsAt, Instant occupiedUntil) {

  public AppointmentOccupancy {
    if (startsAt == null || occupiedUntil == null || !occupiedUntil.isAfter(startsAt)) {
      throw new IllegalArgumentException("Intervalo ocupado inválido");
    }
  }
}
