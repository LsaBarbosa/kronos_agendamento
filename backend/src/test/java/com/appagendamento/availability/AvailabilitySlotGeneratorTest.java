package com.appagendamento.availability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.appagendamento.availability.domain.AvailabilitySlot;
import com.appagendamento.availability.domain.AvailabilitySlotGenerator;
import com.appagendamento.availability.domain.OccupiedInterval;
import com.appagendamento.availability.domain.ScheduleValidationException;
import com.appagendamento.availability.domain.SlotGridAnchor;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class AvailabilitySlotGeneratorTest {

  private static final LocalDate DATE = LocalDate.of(2026, 10, 19);
  private static final ZoneId UTC = ZoneId.of("UTC");

  @Test
  void generatesGridAccordingToExplicitAnchorChoice() {
    List<AvailabilitySlot> fromPeriodStart =
        generate(LocalTime.of(9, 15), LocalTime.NOON, UTC, 30, 30, 0, SlotGridAnchor.PERIOD_START);
    List<AvailabilitySlot> fromWallClock =
        generate(LocalTime.of(9, 15), LocalTime.NOON, UTC, 30, 30, 0, SlotGridAnchor.WALL_CLOCK);

    assertThat(localStarts(fromPeriodStart, UTC))
        .containsExactly("09:15", "09:45", "10:15", "10:45", "11:15");
    assertThat(localStarts(fromWallClock, UTC))
        .containsExactly("09:30", "10:00", "10:30", "11:00", "11:30");
  }

  @Test
  void requiresCompleteServiceAndBufferToFitInsideOnePeriod() {
    List<AvailabilitySlot> slots =
        generate(
            LocalTime.of(9, 0), LocalTime.of(10, 0), UTC, 15, 45, 15, SlotGridAnchor.PERIOD_START);

    assertThat(localStarts(slots, UTC)).containsExactly("09:00");
    assertThat(slots.getFirst().occupiedUntil())
        .isEqualTo(DATE.atTime(10, 0).toInstant(ZoneOffset.UTC));
  }

  @Test
  void adjacentIntervalsDoNotPermitAnOccupancyToCrossTheirBoundary() {
    List<AvailabilitySlot> firstPeriod =
        generate(
            LocalTime.of(9, 0), LocalTime.of(10, 0), UTC, 15, 45, 15, SlotGridAnchor.PERIOD_START);
    List<AvailabilitySlot> secondPeriod =
        generate(
            LocalTime.of(10, 0), LocalTime.of(11, 0), UTC, 15, 45, 15, SlotGridAnchor.PERIOD_START);

    assertThat(localStarts(firstPeriod, UTC)).containsExactly("09:00");
    assertThat(localStarts(secondPeriod, UTC)).containsExactly("10:00");
  }

  @Test
  void overlapUsesHalfOpenIntervalsAndIncludesBuffer() {
    AvailabilitySlot slot =
        generate(
                LocalTime.of(9, 0),
                LocalTime.of(10, 0),
                UTC,
                15,
                30,
                15,
                SlotGridAnchor.PERIOD_START)
            .getFirst();
    OccupiedInterval touchingEnd =
        new OccupiedInterval(slot.occupiedUntil(), slot.occupiedUntil().plusSeconds(600));
    OccupiedInterval touchingStart =
        new OccupiedInterval(slot.startsAt().minusSeconds(600), slot.startsAt());
    OccupiedInterval overlappingBuffer =
        new OccupiedInterval(slot.serviceEndsAt(), slot.occupiedUntil().plusSeconds(60));

    assertThat(
            AvailabilitySlotGenerator.excludeOverlaps(
                List.of(slot), List.of(touchingEnd, touchingStart)))
        .containsExactly(slot);
    assertThat(AvailabilitySlotGenerator.excludeOverlaps(List.of(slot), List.of(overlappingBuffer)))
        .isEmpty();
  }

  @Test
  void omitsNonexistentAndAmbiguousLocalStartsAndRejectsUnresolvedPeriodEdges() {
    ZoneId newYork = ZoneId.of("America/New_York");
    LocalDate springForward = LocalDate.of(2026, 3, 8);
    LocalDate fallBack = LocalDate.of(2026, 11, 1);

    List<AvailabilitySlot> springSlots =
        generate(
            springForward,
            LocalTime.of(1, 0),
            LocalTime.of(4, 0),
            null,
            null,
            newYork,
            30,
            30,
            0,
            SlotGridAnchor.PERIOD_START);
    List<AvailabilitySlot> fallSlots =
        generate(
            fallBack,
            LocalTime.MIDNIGHT,
            LocalTime.of(3, 0),
            null,
            null,
            newYork,
            30,
            30,
            0,
            SlotGridAnchor.PERIOD_START);
    List<AvailabilitySlot> missingPeriodBoundary =
        generate(
            springForward,
            LocalTime.of(1, 0),
            LocalTime.of(2, 30),
            null,
            null,
            newYork,
            30,
            30,
            0,
            SlotGridAnchor.PERIOD_START);

    assertThat(localStarts(springSlots, newYork)).contains("01:00", "01:30", "03:00", "03:30");
    assertThat(localStarts(springSlots, newYork)).doesNotContain("02:00", "02:30");
    assertThat(localStarts(fallSlots, newYork)).contains("00:00", "00:30", "02:00", "02:30");
    assertThat(missingPeriodBoundary).isEmpty();
  }

  @Test
  void explicitValidOffsetsResolvePeriodEdgesButDoNotChooseAmbiguousSlotStarts() {
    ZoneId newYork = ZoneId.of("America/New_York");
    List<AvailabilitySlot> slots =
        generate(
            LocalDate.of(2026, 11, 1),
            LocalTime.of(1, 0),
            LocalTime.of(2, 0),
            ZoneOffset.ofHours(-4),
            ZoneOffset.ofHours(-5),
            newYork,
            15,
            15,
            0,
            SlotGridAnchor.PERIOD_START);

    assertThat(slots).isEmpty();
  }

  @Test
  void generatedSlotsAreDeterministicUniqueAndAlwaysFit() {
    Random random = new Random(13013);
    for (SlotGridAnchor anchor : SlotGridAnchor.values()) {
      for (int iteration = 0; iteration < 100; iteration++) {
        int step = List.of(15, 30, 60).get(random.nextInt(3));
        int duration = 15 * (1 + random.nextInt(8));
        int buffer = 15 * random.nextInt(5);
        List<AvailabilitySlot> first =
            generate(LocalTime.of(8, 15), LocalTime.of(18, 0), UTC, step, duration, buffer, anchor);
        List<AvailabilitySlot> second =
            generate(LocalTime.of(8, 15), LocalTime.of(18, 0), UTC, step, duration, buffer, anchor);

        assertThat(second).isEqualTo(first);
        assertThat(first.stream().map(AvailabilitySlot::startsAt).distinct()).hasSameSizeAs(first);
        assertThat(first)
            .allSatisfy(
                slot -> {
                  assertThat(slot.startsAt())
                      .isAfterOrEqualTo(DATE.atTime(8, 15).toInstant(ZoneOffset.UTC));
                  assertThat(slot.occupiedUntil())
                      .isBeforeOrEqualTo(DATE.atTime(18, 0).toInstant(ZoneOffset.UTC));
                  assertThat(slot.occupiedUntil())
                      .isEqualTo(slot.serviceEndsAt().plusSeconds(buffer * 60L));
                });
      }
    }
  }

  @Test
  void rejectsNonIanaZoneAndInvalidIntervals() {
    assertThatThrownBy(
            () ->
                generate(
                    LocalTime.of(9, 10),
                    LocalTime.of(10, 0),
                    UTC,
                    30,
                    30,
                    0,
                    SlotGridAnchor.PERIOD_START))
        .isInstanceOf(ScheduleValidationException.class);
  }

  private static List<AvailabilitySlot> generate(
      LocalTime start,
      LocalTime end,
      ZoneId zone,
      int step,
      int duration,
      int buffer,
      SlotGridAnchor anchor) {
    return generate(DATE, start, end, null, null, zone, step, duration, buffer, anchor);
  }

  private static List<AvailabilitySlot> generate(
      LocalDate date,
      LocalTime start,
      LocalTime end,
      ZoneOffset startOffset,
      ZoneOffset endOffset,
      ZoneId zone,
      int step,
      int duration,
      int buffer,
      SlotGridAnchor anchor) {
    return AvailabilitySlotGenerator.generate(
        date, start, end, startOffset, endOffset, zone, step, duration, buffer, anchor);
  }

  private static List<String> localStarts(List<AvailabilitySlot> slots, ZoneId zone) {
    return slots.stream().map(slot -> slot.startsAt(zone).toLocalTime().toString()).toList();
  }
}
