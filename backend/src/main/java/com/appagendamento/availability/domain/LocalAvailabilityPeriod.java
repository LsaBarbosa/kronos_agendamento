package com.appagendamento.availability.domain;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;

public record LocalAvailabilityPeriod(
    LocalDate date,
    LocalTime startsAt,
    LocalTime endsAt,
    ZoneOffset startOffset,
    ZoneOffset endOffset) {

  public LocalAvailabilityPeriod {
    if (date == null
        || startsAt == null
        || endsAt == null
        || !startsAt.isBefore(endsAt)
        || startsAt.getSecond() != 0
        || startsAt.getNano() != 0
        || endsAt.getSecond() != 0
        || endsAt.getNano() != 0
        || startsAt.getMinute() % 15 != 0
        || endsAt.getMinute() % 15 != 0) {
      throw new ScheduleValidationException("Período local de disponibilidade inválido");
    }
  }
}
