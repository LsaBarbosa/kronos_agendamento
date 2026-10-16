package com.appagendamento.availability.domain;

import java.time.LocalTime;

public record SpecialPeriod(LocalTime localStart, LocalTime localEnd)
    implements Comparable<SpecialPeriod> {

  public SpecialPeriod {
    if (localStart == null || localEnd == null) {
      throw new ScheduleValidationException("Início e fim do período são obrigatórios");
    }
    if (localStart.getSecond() != 0
        || localStart.getNano() != 0
        || localEnd.getSecond() != 0
        || localEnd.getNano() != 0
        || !localStart.isBefore(localEnd)) {
      throw new ScheduleValidationException("O início deve ser anterior ao fim no mesmo dia");
    }
    if (localStart.getMinute() % 15 != 0 || localEnd.getMinute() % 15 != 0) {
      throw new ScheduleValidationException("Os limites devem estar alinhados a 15 minutos");
    }
  }

  @Override
  public int compareTo(SpecialPeriod other) {
    int startComparison = localStart.compareTo(other.localStart);
    return startComparison != 0 ? startComparison : localEnd.compareTo(other.localEnd);
  }
}
