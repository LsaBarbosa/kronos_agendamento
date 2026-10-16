package com.appagendamento.availability.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public record ScheduleDateRange(LocalDate from, LocalDate to) {

  public ScheduleDateRange {
    if (from == null
        || to == null
        || from.isAfter(to)
        || ChronoUnit.DAYS.between(from, to) > 365
        || to.equals(LocalDate.MAX)) {
      throw new ScheduleValidationException("O intervalo deve conter de 1 a 366 datas válidas");
    }
  }
}
