package com.appagendamento.availability.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

public record AvailabilityTemporalWindow(
    Instant earliestStart,
    BoundaryInclusion earliestInclusion,
    Instant latestStart,
    BoundaryInclusion latestInclusion) {

  public AvailabilityTemporalWindow {
    Objects.requireNonNull(earliestStart);
    Objects.requireNonNull(earliestInclusion);
    Objects.requireNonNull(latestStart);
    Objects.requireNonNull(latestInclusion);
  }

  public static AvailabilityTemporalWindow from(
      Clock clock,
      ZoneId zone,
      AvailabilityPolicy policy,
      BoundaryInclusion earliestInclusion,
      BoundaryInclusion latestInclusion) {
    Objects.requireNonNull(clock);
    Objects.requireNonNull(zone);
    Objects.requireNonNull(policy);
    Instant now = clock.instant();
    Instant earliest = now.plus(Duration.ofHours(policy.minimumNoticeHours()));
    Instant latest = now.atZone(zone).plusDays(policy.bookingHorizonDays()).toInstant();
    return new AvailabilityTemporalWindow(earliest, earliestInclusion, latest, latestInclusion);
  }

  public boolean includes(Instant candidateStart) {
    Objects.requireNonNull(candidateStart);
    boolean afterEarliest =
        earliestInclusion == BoundaryInclusion.INCLUSIVE
            ? !candidateStart.isBefore(earliestStart)
            : candidateStart.isAfter(earliestStart);
    boolean beforeLatest =
        latestInclusion == BoundaryInclusion.INCLUSIVE
            ? !candidateStart.isAfter(latestStart)
            : candidateStart.isBefore(latestStart);
    return afterEarliest && beforeLatest;
  }
}
