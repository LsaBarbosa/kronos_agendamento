package com.appagendamento.appointments.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Intervalo imutável [start, end): adjacência não constitui sobreposição. Políticas de grade,
 * duração e buffer pertencem aos casos de uso futuros.
 */
public record TimeInterval(Instant start, Instant end) {

  public TimeInterval {
    Objects.requireNonNull(start, "start não pode ser nulo");
    Objects.requireNonNull(end, "end não pode ser nulo");
    if (!end.isAfter(start)) {
      throw new IllegalArgumentException("end deve ser posterior a start");
    }
  }

  public boolean overlaps(TimeInterval other) {
    Objects.requireNonNull(other, "other não pode ser nulo");
    return start.isBefore(other.end) && other.start.isBefore(end);
  }

  public Duration duration() {
    return Duration.between(start, end);
  }
}
