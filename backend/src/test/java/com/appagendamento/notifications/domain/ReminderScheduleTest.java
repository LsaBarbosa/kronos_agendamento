package com.appagendamento.notifications.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ReminderScheduleTest {

  @Test
  void calculatesOffsetOnTheTimelineAcrossDayAndDstTransitions() {
    Instant startsAt = Instant.parse("2026-11-01T07:30:00Z");

    Instant dueAt = ReminderSchedule.dueAt(startsAt, 2);

    assertThat(dueAt).isEqualTo(Instant.parse("2026-11-01T05:30:00Z"));
    assertThat(dueAt.atZone(ZoneId.of("America/New_York")).getOffset().getTotalSeconds())
        .isEqualTo(-4 * 60 * 60);
    assertThat(startsAt.atZone(ZoneId.of("America/New_York")).getOffset().getTotalSeconds())
        .isEqualTo(-5 * 60 * 60);
  }

  @Test
  void calculatesElapsedOffsetAcrossSpringDstGap() {
    Instant startsAt = Instant.parse("2026-03-08T07:30:00Z");

    Instant dueAt = ReminderSchedule.dueAt(startsAt, 2);

    assertThat(dueAt).isEqualTo(Instant.parse("2026-03-08T05:30:00Z"));
    assertThat(dueAt.atZone(ZoneId.of("America/New_York")).toLocalTime().toString())
        .isEqualTo("00:30");
    assertThat(startsAt.atZone(ZoneId.of("America/New_York")).toLocalTime().toString())
        .isEqualTo("03:30");
  }

  @Test
  void acceptsOnlyConfiguredReminderOffsets() {
    assertThatThrownBy(() -> ReminderSchedule.dueAt(Instant.parse("2026-03-08T07:30:00Z"), 3))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void delayedReminderIsEligibleOnlyBeforeTheAppointmentInstant() {
    Instant startsAt = Instant.parse("2026-10-19T12:00:00Z");

    assertThat(
            ReminderSchedule.beforeAppointmentStart(
                startsAt, Clock.fixed(Instant.parse("2026-10-19T11:59:59Z"), ZoneOffset.UTC)))
        .isTrue();
    assertThat(
            ReminderSchedule.beforeAppointmentStart(
                startsAt, Clock.fixed(startsAt, ZoneOffset.UTC)))
        .isFalse();
    assertThat(
            ReminderSchedule.beforeAppointmentStart(
                startsAt, Clock.fixed(Instant.parse("2026-10-19T12:00:01Z"), ZoneOffset.UTC)))
        .isFalse();
  }
}
