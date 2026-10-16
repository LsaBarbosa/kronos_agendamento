package com.appagendamento.availability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.appagendamento.availability.domain.AvailabilitySlot;
import com.appagendamento.availability.domain.AvailabilitySlotGenerator;
import com.appagendamento.availability.domain.OccupiedInterval;
import com.appagendamento.availability.domain.ScheduleValidationException;
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
  void anchorsEachGridToItsOwnPeriodStart() {
    List<AvailabilitySlot> firstPeriod =
        generate(LocalTime.of(9, 15), LocalTime.of(10, 45), UTC, 30, 30, 0);
    List<AvailabilitySlot> secondPeriod =
        generate(LocalTime.of(11, 0), LocalTime.NOON, UTC, 30, 30, 0);

    assertThat(localStarts(firstPeriod, UTC)).containsExactly("09:15", "09:45", "10:15");
    assertThat(localStarts(secondPeriod, UTC)).containsExactly("11:00", "11:30");
  }

  @Test
  void requiresCompleteServiceAndBufferToFitInsideOnePeriod() {
    List<AvailabilitySlot> slots =
        generate(LocalTime.of(9, 0), LocalTime.of(10, 0), UTC, 15, 45, 15);

    assertThat(localStarts(slots, UTC)).containsExactly("09:00");
    assertThat(slots.getFirst().occupiedUntil())
        .isEqualTo(DATE.atTime(10, 0).toInstant(ZoneOffset.UTC));
  }

  @Test
  void adjacentIntervalsDoNotPermitAnOccupancyToCrossTheirBoundary() {
    List<AvailabilitySlot> firstPeriod =
        generate(LocalTime.of(9, 0), LocalTime.of(10, 0), UTC, 15, 45, 15);
    List<AvailabilitySlot> secondPeriod =
        generate(LocalTime.of(10, 0), LocalTime.of(11, 0), UTC, 15, 45, 15);

    assertThat(localStarts(firstPeriod, UTC)).containsExactly("09:00");
    assertThat(localStarts(secondPeriod, UTC)).containsExactly("10:00");
  }

  @Test
  void overlapUsesHalfOpenIntervalsAndIncludesBuffer() {
    AvailabilitySlot slot =
        generate(LocalTime.of(9, 0), LocalTime.of(10, 0), UTC, 15, 30, 15).getFirst();
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
            springForward, LocalTime.of(1, 0), LocalTime.of(4, 0), null, null, newYork, 30, 30, 0);
    List<AvailabilitySlot> fallSlots =
        generate(fallBack, LocalTime.MIDNIGHT, LocalTime.of(3, 0), null, null, newYork, 30, 30, 0);
    List<AvailabilitySlot> missingPeriodBoundary =
        generate(
            springForward, LocalTime.of(1, 0), LocalTime.of(2, 30), null, null, newYork, 30, 30, 0);

    assertThat(localStarts(springSlots, newYork)).contains("01:00", "01:30", "03:00", "03:30");
    assertThat(localStarts(springSlots, newYork)).doesNotContain("02:00", "02:30");
    assertThat(localStarts(fallSlots, newYork)).contains("00:00", "00:30", "02:00", "02:30");
    assertThat(missingPeriodBoundary).isEmpty();
  }

  @Test
  void explicitStartOffsetSelectsAmbiguousAnchorButDoesNotSelectLaterAmbiguousCandidates() {
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
            0);

    assertThat(slots).hasSize(1);
    assertThat(slots.getFirst().startsAt())
        .isEqualTo(LocalDate.of(2026, 11, 1).atTime(1, 0).toInstant(ZoneOffset.ofHours(-4)));
  }

  @Test
  void generatedSlotsAreDeterministicUniqueAndAlwaysFit() {
    Random random = new Random(13013);
    for (int iteration = 0; iteration < 100; iteration++) {
      int step = List.of(15, 30, 60).get(random.nextInt(3));
      int duration = 15 * (1 + random.nextInt(8));
      int buffer = 15 * random.nextInt(5);
      List<AvailabilitySlot> first =
          generate(LocalTime.of(8, 15), LocalTime.of(18, 0), UTC, step, duration, buffer);
      List<AvailabilitySlot> second =
          generate(LocalTime.of(8, 15), LocalTime.of(18, 0), UTC, step, duration, buffer);

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

  @Test
  void rejectsNonIanaZoneAndInvalidIntervals() {
    assertThatThrownBy(() -> generate(LocalTime.of(9, 10), LocalTime.of(10, 0), UTC, 30, 30, 0))
        .isInstanceOf(ScheduleValidationException.class);
  }

  private static List<AvailabilitySlot> generate(
      LocalTime start, LocalTime end, ZoneId zone, int step, int duration, int buffer) {
    return generate(DATE, start, end, null, null, zone, step, duration, buffer);
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
      int buffer) {
    return AvailabilitySlotGenerator.generate(
        date, start, end, startOffset, endOffset, zone, step, duration, buffer);
  }

  private static List<String> localStarts(List<AvailabilitySlot> slots, ZoneId zone) {
    return slots.stream().map(slot -> slot.startsAt(zone).toLocalTime().toString()).toList();
  }
}
