package com.appagendamento.availability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.appagendamento.availability.domain.ScheduleValidationException;
import com.appagendamento.availability.domain.WeeklyPeriod;
import com.appagendamento.availability.domain.WeeklySchedule;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WeeklyScheduleTest {

  @Test
  void sortsPeriodsByIsoDayAndLocalStartIndependentOfInputOrder() {
    WeeklyPeriod mondayAfternoon = period(DayOfWeek.MONDAY, "13:00", "17:00");
    WeeklyPeriod tuesdayMorning = period(DayOfWeek.TUESDAY, "09:00", "12:00");
    WeeklyPeriod mondayMorning = period(DayOfWeek.MONDAY, "09:00", "12:00");

    WeeklySchedule schedule =
        new WeeklySchedule(
            UUID.randomUUID(),
            "America/Sao_Paulo",
            true,
            List.of(tuesdayMorning, mondayAfternoon, mondayMorning),
            1);

    assertThat(schedule.periods()).containsExactly(mondayMorning, mondayAfternoon, tuesdayMorning);
  }

  @Test
  void allowsAdjacentPeriodsAndExplicitlyConfiguredClosedWeek() {
    WeeklySchedule schedule =
        new WeeklySchedule(
            UUID.randomUUID(),
            "America/Sao_Paulo",
            true,
            List.of(
                period(DayOfWeek.MONDAY, "09:00", "12:00"),
                period(DayOfWeek.MONDAY, "12:00", "14:00")),
            3);
    WeeklySchedule closed =
        new WeeklySchedule(UUID.randomUUID(), "America/Sao_Paulo", true, List.of(), 1);

    assertThat(schedule.periods()).hasSize(2);
    assertThat(closed.configured()).isTrue();
    assertThat(closed.periods()).isEmpty();
  }

  @Test
  void rejectsOverlapReversedRangesAndUnconfiguredPeriods() {
    assertThatThrownBy(
            () ->
                new WeeklySchedule(
                    UUID.randomUUID(),
                    "America/Sao_Paulo",
                    true,
                    List.of(
                        period(DayOfWeek.MONDAY, "09:00", "12:00"),
                        period(DayOfWeek.MONDAY, "11:59", "14:00")),
                    1))
        .isInstanceOf(ScheduleValidationException.class);
    assertThatThrownBy(() -> period(DayOfWeek.SUNDAY, "23:00", "01:00"))
        .isInstanceOf(ScheduleValidationException.class);
    assertThatThrownBy(
            () ->
                new WeeklySchedule(
                    UUID.randomUUID(),
                    "America/Sao_Paulo",
                    false,
                    List.of(period(DayOfWeek.MONDAY, "09:00", "12:00")),
                    0))
        .isInstanceOf(ScheduleValidationException.class);
  }

  private static WeeklyPeriod period(DayOfWeek day, String start, String end) {
    return new WeeklyPeriod(day, LocalTime.parse(start), LocalTime.parse(end));
  }
}
