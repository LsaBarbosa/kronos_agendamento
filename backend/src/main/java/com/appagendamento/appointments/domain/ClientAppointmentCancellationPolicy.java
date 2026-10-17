package com.appagendamento.appointments.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public final class ClientAppointmentCancellationPolicy {

  public boolean allows(Instant startsAt, Instant now, int cancellationCutoffHours) {
    if (startsAt == null
        || now == null
        || cancellationCutoffHours < 2
        || cancellationCutoffHours > 24) {
      throw new IllegalArgumentException("Parâmetros da política de cancelamento inválidos");
    }
    return now.isBefore(startsAt.minus(cancellationCutoffHours, ChronoUnit.HOURS));
  }
}
