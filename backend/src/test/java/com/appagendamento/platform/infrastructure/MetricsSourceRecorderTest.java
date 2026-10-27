package com.appagendamento.platform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.appagendamento.platform.api.MetricsSourceRecorder;
import com.appagendamento.platform.api.MetricsSourceRecorder.Source;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class MetricsSourceRecorderTest {

  @Test
  void failureAfterSuccessMarksSourceUnavailableAndKeepsLastSuccessfulSample() {
    var registry = new SimpleMeterRegistry();
    var recorder =
        new MetricsSourceRecorder(
            registry, Clock.fixed(Instant.parse("2026-10-26T12:00:00Z"), ZoneOffset.UTC));

    recorder.success(Source.SUBSCRIPTIONS, recorder.start());
    double lastSuccess =
        registry
            .find("agd.metrics.source.last_success.timestamp")
            .tag("source", "subscriptions")
            .gauge()
            .value();
    recorder.failure(Source.SUBSCRIPTIONS, recorder.start(), new IllegalStateException("query"));

    assertThat(
            registry
                .find("agd.metrics.source.sampled")
                .tag("source", "subscriptions")
                .gauge()
                .value())
        .isEqualTo(1.0);
    assertThat(
            registry
                .find("agd.metrics.source.available")
                .tag("source", "subscriptions")
                .gauge()
                .value())
        .isZero();
    assertThat(
            registry
                .find("agd.metrics.source.last_success.timestamp")
                .tag("source", "subscriptions")
                .gauge()
                .value())
        .isEqualTo(lastSuccess);
    assertThat(
            registry
                .find("agd.metrics.source.collection.failures")
                .tag("source", "subscriptions")
                .tag("result", "error")
                .counter()
                .count())
        .isEqualTo(1.0);
  }
}
