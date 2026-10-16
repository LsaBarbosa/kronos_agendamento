package com.appagendamento.availability.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public record AvailabilityDaySchedule(
    UUID tenantId,
    String timeZone,
    boolean configured,
    boolean explicitlyClosed,
    List<LocalAvailabilityPeriod> periods) {

  public AvailabilityDaySchedule {
    if (tenantId == null
        || timeZone == null
        || periods == null
        || periods.stream().anyMatch(java.util.Objects::isNull)
        || (explicitlyClosed && (!configured || !periods.isEmpty()))
        || (!configured && !periods.isEmpty())) {
      throw new ScheduleValidationException("Agenda diária inválida");
    }
    periods = List.copyOf(periods);
    LocalAvailabilityPeriod previous = null;
    for (LocalAvailabilityPeriod period : periods) {
      if (previous != null
          && (!previous.date().equals(period.date())
              || period.startsAt().isBefore(previous.endsAt()))) {
        throw new ScheduleValidationException("Períodos diários inválidos ou sobrepostos");
      }
      previous = period;
    }
  }

  public static AvailabilityDaySchedule resolve(
      UUID tenantId,
      LocalDate date,
      WeeklySchedule weeklySchedule,
      Optional<ScheduleException> exception) {
    if (tenantId == null || date == null || weeklySchedule == null || exception == null) {
      throw new ScheduleValidationException("Fontes da agenda diária são obrigatórias");
    }
    if (!tenantId.equals(weeklySchedule.tenantId())) {
      throw new ScheduleValidationException("Grade semanal pertence a outro tenant");
    }
    if (exception.isPresent()
        && (!tenantId.equals(exception.get().tenantId())
            || !date.equals(exception.get().localDate())
            || !weeklySchedule.timeZone().equals(exception.get().timeZone()))) {
      throw new ScheduleValidationException("Exceção pertence a outra data, tenant ou fuso");
    }
    if (exception.isPresent()) {
      ScheduleException value = exception.get();
      if (value.kind() == ScheduleExceptionKind.CLOSED) {
        return new AvailabilityDaySchedule(
            tenantId, weeklySchedule.timeZone(), true, true, List.of());
      }
      return new AvailabilityDaySchedule(
          tenantId,
          weeklySchedule.timeZone(),
          true,
          false,
          value.periods().stream()
              .map(
                  period ->
                      new LocalAvailabilityPeriod(
                          date,
                          period.localStart(),
                          period.localEnd(),
                          period.localStartOffset(),
                          period.localEndOffset()))
              .toList());
    }
    if (!weeklySchedule.configured()) {
      return new AvailabilityDaySchedule(
          tenantId, weeklySchedule.timeZone(), false, false, List.of());
    }
    return new AvailabilityDaySchedule(
        tenantId,
        weeklySchedule.timeZone(),
        true,
        false,
        weeklySchedule.periods().stream()
            .filter(period -> period.weekday() == date.getDayOfWeek())
            .map(
                period ->
                    new LocalAvailabilityPeriod(
                        date, period.localStart(), period.localEnd(), null, null))
            .toList());
  }
}
