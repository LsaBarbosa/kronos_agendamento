package com.appagendamento.platform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.appagendamento.platform.api.WorkerMetrics.Worker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class MicrometerWorkerMetricsTest {

  @Test
  void noWorkAdvancesHeartbeatButNotProgressAndNoSampleExistsBeforeFirstPoll() {
    var registry = new SimpleMeterRegistry();
    var metrics = new MicrometerWorkerMetrics(registry, fixedClock());

    assertThat(registry.find("agd.worker.heartbeat.timestamp").tag("worker", "outbox").gauge())
        .isNull();
    assertThat(metrics.execute(Worker.OUTBOX, () -> 0)).isZero();

    assertThat(
            registry.find("agd.worker.heartbeat.timestamp").tag("worker", "outbox").gauge().value())
        .isEqualTo(Instant.parse("2026-10-26T12:00:00Z").getEpochSecond());
    assertThat(registry.find("agd.worker.progress.timestamp").tag("worker", "outbox").gauge())
        .isNull();
    assertThat(registry.find("agd.worker.executions").tag("result", "no_work").counter().count())
        .isEqualTo(1.0);
  }

  @Test
  void completedWorkAdvancesProgressAndFailureAdvancesHeartbeatWithoutInventingProgress() {
    var registry = new SimpleMeterRegistry();
    var metrics = new MicrometerWorkerMetrics(registry, fixedClock());

    assertThat(metrics.execute(Worker.REMINDERS, () -> 2)).isEqualTo(2);
    double progress =
        registry.find("agd.worker.progress.timestamp").tag("worker", "reminders").gauge().value();
    assertThat(progress).isEqualTo(Instant.parse("2026-10-26T12:00:00Z").getEpochSecond());

    assertThatThrownBy(
            () ->
                metrics.execute(
                    Worker.REMINDERS,
                    () -> {
                      throw new IllegalStateException("synthetic");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(registry.find("agd.worker.executions").tag("result", "failed").counter().count())
        .isEqualTo(1.0);
    assertThat(
            registry
                .find("agd.worker.progress.timestamp")
                .tag("worker", "reminders")
                .gauge()
                .value())
        .isEqualTo(progress);
  }

  @Test
  void telemetryClockFailureDoesNotChangeWorkerResult() {
    Clock brokenClock = mock(Clock.class);
    when(brokenClock.instant()).thenReturn(null);
    var registry = new SimpleMeterRegistry();
    var metrics = new MicrometerWorkerMetrics(registry, brokenClock);

    assertThat(metrics.execute(Worker.OUTBOX, () -> 3)).isEqualTo(3);
    assertThat(
            registry
                .find("agd.worker.executions")
                .tag("worker", "outbox")
                .tag("result", "completed")
                .counter())
        .isNull();
    assertThat(registry.find("agd.worker.heartbeat.timestamp").tag("worker", "outbox").gauge())
        .isNull();
  }

  private static Clock fixedClock() {
    return Clock.fixed(Instant.parse("2026-10-26T12:00:00Z"), ZoneOffset.UTC);
  }
}
