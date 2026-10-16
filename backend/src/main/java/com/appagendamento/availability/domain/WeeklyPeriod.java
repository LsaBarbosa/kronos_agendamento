package com.appagendamento.availability.domain;

import java.time.DayOfWeek;
import java.time.LocalTime;

public record WeeklyPeriod(DayOfWeek weekday, LocalTime localStart, LocalTime localEnd)
    implements Comparable<WeeklyPeriod> {

  public WeeklyPeriod {
    if (weekday == null || localStart == null || localEnd == null) {
      throw new ScheduleValidationException("Dia e limites do período são obrigatórios");
    }
    if (localStart.getSecond() != 0
        || localStart.getNano() != 0
        || localEnd.getSecond() != 0
        || localEnd.getNano() != 0
        || !localStart.isBefore(localEnd)) {
      throw new ScheduleValidationException("Período deve ter início antes do fim no mesmo dia");
    }
    if (localStart.getMinute() % 15 != 0 || localEnd.getMinute() % 15 != 0) {
      throw new ScheduleValidationException("Limites do período devem alinhar a cada 15 minutos");
    }
  }

  @Override
  public int compareTo(WeeklyPeriod other) {
    int dayComparison = Integer.compare(weekday.getValue(), other.weekday.getValue());
    if (dayComparison != 0) {
      return dayComparison;
    }
    int startComparison = localStart.compareTo(other.localStart);
    return startComparison != 0 ? startComparison : localEnd.compareTo(other.localEnd);
  }
}
