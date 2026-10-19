package com.appagendamento.notifications.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

public final class ReminderSchedule {

  private static final Set<Integer> ALLOWED_OFFSETS_HOURS = Set.of(2, 6, 12, 24);

  private ReminderSchedule() {}

  public static Instant dueAt(Instant appointmentStartsAt, int offsetHours) {
    if (appointmentStartsAt == null || !ALLOWED_OFFSETS_HOURS.contains(offsetHours)) {
      throw new IllegalArgumentException("Dados do lembrete inválidos");
    }
    return appointmentStartsAt.minus(offsetHours, ChronoUnit.HOURS);
  }

  public static boolean beforeAppointmentStart(Instant appointmentStartsAt, Clock clock) {
    if (appointmentStartsAt == null || clock == null) {
      throw new IllegalArgumentException("Dados temporais do lembrete inválidos");
    }
    return appointmentStartsAt.isAfter(clock.instant());
  }
}
