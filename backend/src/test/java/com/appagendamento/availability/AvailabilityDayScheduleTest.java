package com.appagendamento.availability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.appagendamento.availability.domain.AvailabilityDaySchedule;
import com.appagendamento.availability.domain.ScheduleException;
import com.appagendamento.availability.domain.ScheduleExceptionKind;
import com.appagendamento.availability.domain.ScheduleValidationException;
import com.appagendamento.availability.domain.SpecialPeriod;
import com.appagendamento.availability.domain.WeeklyPeriod;
import com.appagendamento.availability.domain.WeeklySchedule;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AvailabilityDayScheduleTest {

  private static final LocalDate MONDAY = LocalDate.of(2026, 10, 19);

  @Test
  void distinguishesMissingWeeklyConfigurationFromConfiguredClosedWeek() {
    UUID tenantId = UUID.randomUUID();
    WeeklySchedule missing = new WeeklySchedule(tenantId, "UTC", false, List.of(), 0);
    WeeklySchedule closed = new WeeklySchedule(tenantId, "UTC", true, List.of(), 1);

    AvailabilityDaySchedule missingDay =
        AvailabilityDaySchedule.resolve(tenantId, MONDAY, missing, Optional.empty());
    AvailabilityDaySchedule closedDay =
        AvailabilityDaySchedule.resolve(tenantId, MONDAY, closed, Optional.empty());

    assertThat(missingDay.configured()).isFalse();
    assertThat(missingDay.explicitlyClosed()).isFalse();
    assertThat(closedDay.configured()).isTrue();
    assertThat(closedDay.explicitlyClosed()).isFalse();
    assertThat(closedDay.periods()).isEmpty();
  }

  @Test
  void usesWeeklyPeriodsWhenNoDateExceptionExists() {
    UUID tenantId = UUID.randomUUID();
    WeeklySchedule weekly = weekly(tenantId);

    AvailabilityDaySchedule day =
        AvailabilityDaySchedule.resolve(tenantId, MONDAY, weekly, Optional.empty());

    assertThat(day.configured()).isTrue();
    assertThat(day.explicitlyClosed()).isFalse();
    assertThat(day.periods()).extracting("startsAt").containsExactly(LocalTime.of(9, 0));
  }

  @Test
  void closedExceptionReplacesWeeklyPeriodsAndSpecialExceptionIsTheFinalList() {
    UUID tenantId = UUID.randomUUID();
    WeeklySchedule weekly = weekly(tenantId);
    ScheduleException closed = exception(tenantId, ScheduleExceptionKind.CLOSED, List.of());
    ScheduleException special =
        exception(
            tenantId,
            ScheduleExceptionKind.SPECIAL,
            List.of(new SpecialPeriod(LocalTime.of(13, 0), LocalTime.of(14, 0))));

    AvailabilityDaySchedule closedDay =
        AvailabilityDaySchedule.resolve(tenantId, MONDAY, weekly, Optional.of(closed));
    AvailabilityDaySchedule specialDay =
        AvailabilityDaySchedule.resolve(tenantId, MONDAY, weekly, Optional.of(special));

    assertThat(closedDay)
        .isEqualTo(new AvailabilityDaySchedule(tenantId, "UTC", true, true, List.of()));
    assertThat(specialDay.periods()).hasSize(1);
    assertThat(specialDay.periods().getFirst().startsAt()).isEqualTo(LocalTime.of(13, 0));
  }

  @Test
  void rejectsExceptionFromAnotherTenantOrDate() {
    UUID tenantId = UUID.randomUUID();
    ScheduleException exception = exception(tenantId, ScheduleExceptionKind.CLOSED, List.of());

    assertThatThrownBy(
            () ->
                AvailabilityDaySchedule.resolve(
                    UUID.randomUUID(), MONDAY, weekly(tenantId), Optional.of(exception)))
        .isInstanceOf(ScheduleValidationException.class);
    assertThatThrownBy(
            () ->
                AvailabilityDaySchedule.resolve(
                    tenantId, MONDAY.plusDays(1), weekly(tenantId), Optional.of(exception)))
        .isInstanceOf(ScheduleValidationException.class);
  }

  private static WeeklySchedule weekly(UUID tenantId) {
    return new WeeklySchedule(
        tenantId,
        "UTC",
        true,
        List.of(new WeeklyPeriod(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.NOON)),
        1);
  }

  private static ScheduleException exception(
      UUID tenantId, ScheduleExceptionKind kind, List<SpecialPeriod> periods) {
    return new ScheduleException(
        UUID.randomUUID(), tenantId, MONDAY, kind, periods, null, 1, "UTC");
  }
}
