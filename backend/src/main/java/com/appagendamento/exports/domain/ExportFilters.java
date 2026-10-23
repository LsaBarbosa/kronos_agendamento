package com.appagendamento.exports.domain;

import com.appagendamento.appointments.domain.AppointmentStatus;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

public record ExportFilters(
    LocalDate from, LocalDate to, Set<AppointmentStatus> statuses, UUID serviceId) {

  public ExportFilters {
    if (from == null || to == null || from.isAfter(to)) {
      throw new InvalidExportRequestException();
    }
    if (java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1 > 366) {
      throw new InvalidExportRequestException();
    }
    try {
      to.plusDays(1);
    } catch (java.time.DateTimeException exception) {
      throw new InvalidExportRequestException();
    }
    statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
  }
}
