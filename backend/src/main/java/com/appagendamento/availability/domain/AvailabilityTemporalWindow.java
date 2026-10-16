package com.appagendamento.availability.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

public record AvailabilityTemporalWindow(
    Instant earliestStart, LocalDate lastEligibleDate, ZoneId timeZone) {

  public AvailabilityTemporalWindow {
    Objects.requireNonNull(earliestStart);
    Objects.requireNonNull(lastEligibleDate);
    Objects.requireNonNull(timeZone);
  }

  public static AvailabilityTemporalWindow from(
      Instant capturedNow, ZoneId zone, AvailabilityPolicy policy) {
    Objects.requireNonNull(capturedNow);
    Objects.requireNonNull(zone);
    Objects.requireNonNull(policy);
    Instant earliest = capturedNow.plus(Duration.ofHours(policy.minimumNoticeHours()));
    LocalDate today = capturedNow.atZone(zone).toLocalDate();
    LocalDate lastEligible = today.plusDays(policy.bookingHorizonDays() - 1L);
    return new AvailabilityTemporalWindow(earliest, lastEligible, zone);
  }

  public boolean includes(Instant candidateStart) {
    Objects.requireNonNull(candidateStart);
    return !candidateStart.isBefore(earliestStart)
        && !candidateStart.atZone(timeZone).toLocalDate().isAfter(lastEligibleDate);
  }
}
