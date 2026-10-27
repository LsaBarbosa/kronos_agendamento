package com.appagendamento.appointments.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class BookingMetricsTest {

  @Test
  void replayAndConflictAreCountedWithoutInventingConfirmedEffects() {
    var registry = new SimpleMeterRegistry();
    var metrics = new BookingMetrics(registry);

    metrics.attempt("public");
    metrics.created("public", false, "created");
    metrics.attempt("public");
    metrics.created("public", true, "created");
    metrics.conflict("public");

    assertThat(
            registry
                .find("agd.appointments.booking.attempts")
                .tag("channel", "public")
                .counter()
                .count())
        .isEqualTo(2.0);
    assertThat(
            registry
                .find("agd.appointments.booking.commands")
                .tag("channel", "public")
                .tag("result", "created")
                .counter()
                .count())
        .isEqualTo(1.0);
    assertThat(
            registry
                .find("agd.appointments.booking.commands")
                .tag("channel", "public")
                .tag("result", "replay")
                .counter()
                .count())
        .isEqualTo(1.0);
    assertThat(
            registry
                .find("agd.appointments.booking.commands")
                .tag("channel", "public")
                .tag("result", "conflict")
                .counter()
                .count())
        .isEqualTo(1.0);
    assertThat(
            registry
                .find("agd.appointments.booking.effects.confirmed")
                .tag("channel", "public")
                .tag("effect", "created")
                .counter()
                .count())
        .isEqualTo(1.0);
  }
}
