package com.appagendamento.availability.domain;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record WeeklySchedule(
    UUID tenantId, String timeZone, boolean configured, List<WeeklyPeriod> periods, long version) {

  public WeeklySchedule {
    if (tenantId == null || timeZone == null || version < 0 || periods == null) {
      throw new ScheduleValidationException("Configuração semanal inválida");
    }
    if (!ZoneId.getAvailableZoneIds().contains(timeZone)) {
      throw new ScheduleValidationException("Fuso do tenant não é uma zona IANA reconhecida");
    }
    periods = canonicalPeriods(periods);
    if (!configured && (!periods.isEmpty() || version != 0)) {
      throw new ScheduleValidationException("Agenda não configurada não pode conter períodos");
    }
    validateNoOverlap(periods);
  }

  public static List<WeeklyPeriod> canonicalPeriods(List<WeeklyPeriod> periods) {
    if (periods == null || periods.stream().anyMatch(java.util.Objects::isNull)) {
      throw new ScheduleValidationException("Períodos são obrigatórios");
    }
    List<WeeklyPeriod> sorted = new ArrayList<>(periods);
    sorted.sort(WeeklyPeriod::compareTo);
    return List.copyOf(sorted);
  }

  private static void validateNoOverlap(List<WeeklyPeriod> periods) {
    WeeklyPeriod previous = null;
    for (WeeklyPeriod period : periods) {
      if (previous != null
          && previous.weekday() == period.weekday()
          && period.localStart().isBefore(previous.localEnd())) {
        throw new ScheduleValidationException("Períodos do mesmo dia não podem se sobrepor");
      }
      previous = period;
    }
  }
}
