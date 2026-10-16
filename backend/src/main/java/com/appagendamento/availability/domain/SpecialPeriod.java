package com.appagendamento.availability.domain;

import java.time.LocalTime;
import java.time.ZoneOffset;

public record SpecialPeriod(
    LocalTime localStart,
    LocalTime localEnd,
    ZoneOffset localStartOffset,
    ZoneOffset localEndOffset)
    implements Comparable<SpecialPeriod> {

  public SpecialPeriod(LocalTime localStart, LocalTime localEnd) {
    this(localStart, localEnd, null, null);
  }

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
    if (startComparison != 0) return startComparison;
    int endComparison = localEnd.compareTo(other.localEnd);
    if (endComparison != 0) return endComparison;
    int startOffsetComparison =
        offsetValue(localStartOffset).compareTo(offsetValue(other.localStartOffset));
    return startOffsetComparison != 0
        ? startOffsetComparison
        : offsetValue(localEndOffset).compareTo(offsetValue(other.localEndOffset));
  }

  private static String offsetValue(ZoneOffset value) {
    return value == null ? "" : value.getId();
  }
}
