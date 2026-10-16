package com.appagendamento.availability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.appagendamento.availability.domain.AvailabilityDaySchedule;
import com.appagendamento.availability.domain.AvailabilityPolicy;
import com.appagendamento.availability.domain.AvailabilityService;
import com.appagendamento.availability.domain.AvailabilitySlotCalculator;
import com.appagendamento.availability.domain.AvailabilityTemporalWindow;
import com.appagendamento.availability.domain.BoundaryInclusion;
import com.appagendamento.availability.domain.OccupiedInterval;
import com.appagendamento.availability.domain.ScheduleValidationException;
import com.appagendamento.availability.domain.SlotGridAnchor;
import com.appagendamento.availability.domain.WeeklyPeriod;
import com.appagendamento.availability.domain.WeeklySchedule;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
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
  void excludesSlotsIntersectingBlockedOrReservedIntervalsButAllowsAdjacency() {
    UUID tenantId = UUID.randomUUID();
    AvailabilityDaySchedule day =
        AvailabilityDaySchedule.resolve(tenantId, MONDAY, weekly(tenantId), Optional.empty());
    AvailabilityService service =
        new AvailabilityService(UUID.randomUUID(), tenantId, true, 30, 15);
    AvailabilityPolicy policy = policy(tenantId);
    Instant blockStart = MONDAY.atTime(9, 45).atZone(ZONE).toInstant();

    var result =
        AvailabilitySlotCalculator.calculate(
            day,
            Optional.of(policy),
            service,
            ZONE,
            List.of(
                new OccupiedInterval(blockStart, MONDAY.atTime(10, 15).atZone(ZONE).toInstant())),
            SlotGridAnchor.PERIOD_START);

    assertThat(localStarts(result.slots()))
        .contains("09:00", "10:30", "11:00")
        .doesNotContain("09:30", "10:00");
    assertThat(result.ready()).isTrue();
  }

  @Test
  void reportsConfigurationReadinessWithoutInventingDefaults() {
    UUID tenantId = UUID.randomUUID();
    AvailabilityDaySchedule day =
        AvailabilityDaySchedule.resolve(
            tenantId,
            MONDAY,
            new WeeklySchedule(tenantId, ZONE.getId(), false, List.of(), 0),
            Optional.empty());
    AvailabilityService service = new AvailabilityService(UUID.randomUUID(), tenantId, true, 30, 0);

    var missingSchedule =
        AvailabilitySlotCalculator.calculate(
            day,
            Optional.of(policy(tenantId)),
            service,
            ZONE,
            List.of(),
            SlotGridAnchor.PERIOD_START);
    var missingPolicy =
        AvailabilitySlotCalculator.calculate(
            AvailabilityDaySchedule.resolve(tenantId, MONDAY, weekly(tenantId), Optional.empty()),
            Optional.empty(),
            service,
            ZONE,
            List.of(),
            SlotGridAnchor.PERIOD_START);

    assertThat(missingSchedule.ready()).isFalse();
    assertThat(missingSchedule.slots()).isEmpty();
    assertThat(missingPolicy.ready()).isFalse();
    assertThat(missingPolicy.slots()).isEmpty();
  }

  @Test
  void inactiveServiceDoesNotProduceSlots() {
    UUID tenantId = UUID.randomUUID();
    var result =
        AvailabilitySlotCalculator.calculate(
            AvailabilityDaySchedule.resolve(tenantId, MONDAY, weekly(tenantId), Optional.empty()),
            Optional.of(policy(tenantId)),
            new AvailabilityService(UUID.randomUUID(), tenantId, false, 30, 0),
            ZONE,
            List.of(),
            SlotGridAnchor.PERIOD_START);

    assertThat(result.ready()).isTrue();
    assertThat(result.slots()).isEmpty();
  }

  @Test
  void rejectsMixedTenantAndUntrustedTimezoneInputs() {
    UUID tenantId = UUID.randomUUID();
    AvailabilityDaySchedule day =
        AvailabilityDaySchedule.resolve(tenantId, MONDAY, weekly(tenantId), Optional.empty());

    assertThatThrownBy(
            () ->
                AvailabilitySlotCalculator.calculate(
                    day,
                    Optional.of(policy(UUID.randomUUID())),
                    new AvailabilityService(UUID.randomUUID(), tenantId, true, 30, 0),
                    ZONE,
                    List.of(),
                    SlotGridAnchor.PERIOD_START))
        .isInstanceOf(ScheduleValidationException.class);
    assertThatThrownBy(
            () ->
                AvailabilitySlotCalculator.calculate(
                    day,
                    Optional.of(policy(tenantId)),
                    new AvailabilityService(UUID.randomUUID(), tenantId, true, 30, 0),
                    ZoneId.of("UTC"),
                    List.of(),
                    SlotGridAnchor.PERIOD_START))
        .isInstanceOf(ScheduleValidationException.class);
  }

  @Test
  void calculatesNoticeInElapsedHoursAndHorizonInTenantCalendarDaysWithInjectedClock() {
    UUID tenantId = UUID.randomUUID();
    AvailabilityPolicy policy = new AvailabilityPolicy(tenantId, 30, 1, 7, 2, 2, 1);
    Clock clock = Clock.fixed(Instant.parse("2026-03-07T17:00:00Z"), ZoneId.of("UTC"));

    var inclusive =
        AvailabilityTemporalWindow.from(
            clock,
            ZoneId.of("America/New_York"),
            policy,
            BoundaryInclusion.INCLUSIVE,
            BoundaryInclusion.INCLUSIVE);
    var strict =
        AvailabilityTemporalWindow.from(
            clock,
            ZoneId.of("America/New_York"),
            policy,
            BoundaryInclusion.EXCLUSIVE,
            BoundaryInclusion.EXCLUSIVE);

    assertThat(inclusive.earliestStart()).isEqualTo(Instant.parse("2026-03-07T18:00:00Z"));
    assertThat(inclusive.latestStart()).isEqualTo(Instant.parse("2026-03-14T16:00:00Z"));
    assertThat(Duration.between(clock.instant(), inclusive.latestStart()).toHours()).isEqualTo(167);
    assertThat(inclusive.includes(inclusive.earliestStart())).isTrue();
    assertThat(inclusive.includes(inclusive.latestStart())).isTrue();
    assertThat(strict.includes(strict.earliestStart())).isFalse();
    assertThat(strict.includes(strict.latestStart())).isFalse();
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
