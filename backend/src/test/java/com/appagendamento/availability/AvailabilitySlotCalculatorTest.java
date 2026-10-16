package com.appagendamento.availability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.appagendamento.availability.domain.AvailabilityDaySchedule;
import com.appagendamento.availability.domain.AvailabilityPolicy;
import com.appagendamento.availability.domain.AvailabilityService;
import com.appagendamento.availability.domain.AvailabilitySlotCalculator;
import com.appagendamento.availability.domain.AvailabilityTemporalWindow;
import com.appagendamento.availability.domain.OccupiedInterval;
import com.appagendamento.availability.domain.ScheduleException;
import com.appagendamento.availability.domain.ScheduleExceptionKind;
import com.appagendamento.availability.domain.ScheduleValidationException;
import com.appagendamento.availability.domain.WeeklyPeriod;
import com.appagendamento.availability.domain.WeeklySchedule;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AvailabilitySlotCalculatorTest {

  private static final LocalDate MONDAY = LocalDate.of(2026, 10, 19);
  private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

  @Test
  void exclusionsFilterAnchoredCandidatesWithoutMovingTheGrid() {
    UUID tenantId = UUID.randomUUID();
    var day = AvailabilityDaySchedule.resolve(tenantId, MONDAY, weekly(tenantId), Optional.empty());
    var result =
        AvailabilitySlotCalculator.calculate(
            day,
            Optional.of(policy(tenantId)),
            new AvailabilityService(UUID.randomUUID(), tenantId, true, 30, 0),
            ZONE,
            List.of(
                new OccupiedInterval(
                    MONDAY.atTime(9, 15).atZone(ZONE).toInstant(),
                    MONDAY.atTime(9, 45).atZone(ZONE).toInstant())));

    assertThat(localStarts(result.slots()))
        .containsExactly("10:00", "10:30", "11:00", "11:30", "13:00", "13:30");
  }

  @Test
  void eachPeriodHasItsOwnAnchorAndOccupationMayEndExactlyAtPeriodEnd() {
    UUID tenantId = UUID.randomUUID();
    var weekly =
        new WeeklySchedule(
            tenantId,
            ZONE.getId(),
            true,
            List.of(
                new WeeklyPeriod(DayOfWeek.MONDAY, LocalTime.of(9, 15), LocalTime.of(10, 15)),
                new WeeklyPeriod(DayOfWeek.MONDAY, LocalTime.of(11, 0), LocalTime.NOON)),
            1);
    var result =
        AvailabilitySlotCalculator.calculate(
            AvailabilityDaySchedule.resolve(tenantId, MONDAY, weekly, Optional.empty()),
            Optional.of(policy(tenantId)),
            new AvailabilityService(UUID.randomUUID(), tenantId, true, 60, 0),
            ZONE,
            List.of());

    assertThat(localStarts(result.slots())).containsExactly("09:15", "11:00");
    assertThat(result.slots().getFirst().serviceEndsAt())
        .isEqualTo(MONDAY.atTime(10, 15).atZone(ZONE).toInstant());
  }

  @Test
  void specialExceptionPeriodsUseTheirOwnStartAsGridAnchor() {
    UUID tenantId = UUID.randomUUID();
    var special =
        new ScheduleException(
            UUID.randomUUID(),
            tenantId,
            MONDAY,
            ScheduleExceptionKind.SPECIAL,
            List.of(
                new com.appagendamento.availability.domain.SpecialPeriod(
                    LocalTime.of(9, 15), LocalTime.of(10, 45))),
            null,
            1,
            ZONE.getId());
    var day =
        AvailabilityDaySchedule.resolve(tenantId, MONDAY, weekly(tenantId), Optional.of(special));
    var result =
        AvailabilitySlotCalculator.calculate(
            day,
            Optional.of(policy(tenantId)),
            new AvailabilityService(UUID.randomUUID(), tenantId, true, 30, 0),
            ZONE,
            List.of());

    assertThat(localStarts(result.slots())).containsExactly("09:15", "09:45", "10:15");
  }

  @Test
  void missingScheduleAndPolicyRemainUnconfiguredAndInactiveServiceHasNoSlots() {
    UUID tenantId = UUID.randomUUID();
    var service = new AvailabilityService(UUID.randomUUID(), tenantId, true, 30, 0);
    var missingSchedule =
        AvailabilitySlotCalculator.calculate(
            AvailabilityDaySchedule.resolve(
                tenantId,
                MONDAY,
                new WeeklySchedule(tenantId, ZONE.getId(), false, List.of(), 0),
                Optional.empty()),
            Optional.of(policy(tenantId)),
            service,
            ZONE,
            List.of());
    var missingPolicy =
        AvailabilitySlotCalculator.calculate(
            AvailabilityDaySchedule.resolve(tenantId, MONDAY, weekly(tenantId), Optional.empty()),
            Optional.empty(),
            service,
            ZONE,
            List.of());
    var inactive =
        AvailabilitySlotCalculator.calculate(
            AvailabilityDaySchedule.resolve(tenantId, MONDAY, weekly(tenantId), Optional.empty()),
            Optional.of(policy(tenantId)),
            new AvailabilityService(UUID.randomUUID(), tenantId, false, 30, 0),
            ZONE,
            List.of());

    assertThat(missingSchedule.ready()).isFalse();
    assertThat(missingPolicy.ready()).isFalse();
    assertThat(inactive.ready()).isTrue();
    assertThat(inactive.slots()).isEmpty();
  }

  @Test
  void rejectsMixedTenantAndUntrustedTimezoneInputs() {
    UUID tenantId = UUID.randomUUID();
    var day = AvailabilityDaySchedule.resolve(tenantId, MONDAY, weekly(tenantId), Optional.empty());

    assertThatThrownBy(
            () ->
                AvailabilitySlotCalculator.calculate(
                    day,
                    Optional.of(policy(UUID.randomUUID())),
                    new AvailabilityService(UUID.randomUUID(), tenantId, true, 30, 0),
                    ZONE,
                    List.of()))
        .isInstanceOf(ScheduleValidationException.class);
    assertThatThrownBy(
            () ->
                AvailabilitySlotCalculator.calculate(
                    day,
                    Optional.of(policy(tenantId)),
                    new AvailabilityService(UUID.randomUUID(), tenantId, true, 30, 0),
                    ZoneId.of("UTC"),
                    List.of()))
        .isInstanceOf(ScheduleValidationException.class);
  }

  @Test
  void appliesInclusiveElapsedNoticeAndLocalCalendarHorizon() {
    AvailabilityPolicy policy = new AvailabilityPolicy(UUID.randomUUID(), 30, 1, 7, 2, 2, 1);
    Instant captured = Instant.parse("2026-10-16T12:00:00Z");
    ZoneId tenantZone = ZoneId.of("America/Sao_Paulo");
    var window = AvailabilityTemporalWindow.from(captured, tenantZone, policy);

    assertThat(window.earliestStart()).isEqualTo(Instant.parse("2026-10-16T13:00:00Z"));
    assertThat(window.lastEligibleDate()).isEqualTo(LocalDate.of(2026, 10, 22));
    assertThat(window.includes(window.earliestStart())).isTrue();
    assertThat(window.includes(window.earliestStart().minusNanos(1))).isFalse();
    assertThat(window.includes(Instant.parse("2026-10-23T02:59:59Z"))).isTrue();
    assertThat(window.includes(Instant.parse("2026-10-23T03:00:00Z"))).isFalse();
  }

  @Test
  void countsCalendarDatesAcrossMonthYearLeapDayAndDstRatherThanFixedHours() {
    AvailabilityPolicy sevenDays = new AvailabilityPolicy(UUID.randomUUID(), 15, 1, 7, 2, 2, 1);
    var monthEnd =
        AvailabilityTemporalWindow.from(
            Instant.parse("2026-01-30T12:00:00Z"), ZoneId.of("UTC"), sevenDays);
    var leapYear =
        AvailabilityTemporalWindow.from(
            Instant.parse("2024-02-27T12:00:00Z"), ZoneId.of("UTC"), sevenDays);
    var yearEnd =
        AvailabilityTemporalWindow.from(
            Instant.parse("2026-12-29T12:00:00Z"), ZoneId.of("UTC"), sevenDays);
    var dst =
        AvailabilityTemporalWindow.from(
            Instant.parse("2026-03-07T17:00:00Z"), ZoneId.of("America/New_York"), sevenDays);

    assertThat(monthEnd.lastEligibleDate()).isEqualTo(LocalDate.of(2026, 2, 5));
    assertThat(leapYear.lastEligibleDate()).isEqualTo(LocalDate.of(2024, 3, 4));
    assertThat(yearEnd.lastEligibleDate()).isEqualTo(LocalDate.of(2027, 1, 4));
    assertThat(dst.lastEligibleDate()).isEqualTo(LocalDate.of(2026, 3, 13));
    assertThat(dst.includes(Instant.parse("2026-03-14T03:59:59Z"))).isTrue();
    assertThat(dst.includes(Instant.parse("2026-03-14T04:00:00Z"))).isFalse();
  }

  @Test
  void determinesTodayFromTenantZoneWhenClockIsNearLocalMidnight() {
    AvailabilityPolicy policy = new AvailabilityPolicy(UUID.randomUUID(), 15, 1, 7, 2, 2, 1);
    var window =
        AvailabilityTemporalWindow.from(Instant.parse("2026-10-17T02:59:59Z"), ZONE, policy);

    assertThat(window.earliestStart()).isEqualTo(Instant.parse("2026-10-17T03:59:59Z"));
    assertThat(window.lastEligibleDate()).isEqualTo(LocalDate.of(2026, 10, 22));
  }

  private static WeeklySchedule weekly(UUID tenantId) {
    return new WeeklySchedule(
        tenantId,
        ZONE.getId(),
        true,
        List.of(
            new WeeklyPeriod(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.NOON),
            new WeeklyPeriod(DayOfWeek.MONDAY, LocalTime.of(13, 0), LocalTime.of(14, 0))),
        1);
  }

  private static AvailabilityPolicy policy(UUID tenantId) {
    return new AvailabilityPolicy(tenantId, 30, 1, 7, 2, 2, 1);
  }

  private static List<String> localStarts(
      List<com.appagendamento.availability.domain.AvailabilitySlot> slots) {
    return slots.stream().map(slot -> slot.startsAt(ZONE).toLocalTime().toString()).toList();
  }
}
