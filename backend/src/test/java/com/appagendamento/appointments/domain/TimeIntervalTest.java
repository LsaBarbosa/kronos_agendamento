package com.appagendamento.appointments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TimeIntervalTest {

  private final Instant start = Instant.parse("2026-10-14T13:00:00Z");

  @Test
  void adjacentIntervalsDoNotOverlap() {
    TimeInterval first = new TimeInterval(start, start.plusSeconds(3600));
    TimeInterval next = new TimeInterval(first.end(), first.end().plusSeconds(900));
    assertThat(first.overlaps(next)).isFalse();
    assertThat(next.overlaps(first)).isFalse();
  }

  @Test
  void overlappingIntervalsAreSymmetric() {
    TimeInterval first = new TimeInterval(start, start.plusSeconds(3600));
    TimeInterval second = new TimeInterval(start.plusSeconds(1800), start.plusSeconds(5400));
    assertThat(first.overlaps(second)).isTrue();
    assertThat(second.overlaps(first)).isTrue();
  }

  @Test
  void containedAndIdenticalIntervalsOverlap() {
    TimeInterval outer = new TimeInterval(start, start.plusSeconds(3600));
    TimeInterval inner = new TimeInterval(start.plusSeconds(60), start.plusSeconds(120));
    assertThat(outer.overlaps(inner)).isTrue();
    assertThat(inner.overlaps(outer)).isTrue();
    assertThat(outer.overlaps(outer)).isTrue();
  }

  @Test
  void separatedIntervalsDoNotOverlap() {
    TimeInterval first = new TimeInterval(start, start.plusSeconds(60));
    TimeInterval second = new TimeInterval(start.plusSeconds(120), start.plusSeconds(180));
    assertThat(first.overlaps(second)).isFalse();
  }

  @Test
  void zeroOrNegativeDurationIsRejected() {
    assertThatIllegalArgumentException().isThrownBy(() -> new TimeInterval(start, start));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new TimeInterval(start, start.minusSeconds(1)));
  }

  @Test
  void nullBoundariesAndComparisonAreRejected() {
    assertThatNullPointerException().isThrownBy(() -> new TimeInterval(null, start));
    assertThatNullPointerException().isThrownBy(() -> new TimeInterval(start, null));
    TimeInterval interval = new TimeInterval(start, start.plusSeconds(60));
    assertThatNullPointerException().isThrownBy(() -> interval.overlaps(null));
  }

  @Test
  void durationUsesInstantsAndNotLocalTimezone() {
    TimeInterval interval = new TimeInterval(start, start.plusSeconds(4500));
    assertThat(interval.duration()).isEqualTo(Duration.ofMinutes(75));
  }
}
