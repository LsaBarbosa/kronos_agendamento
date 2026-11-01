package com.appagendamento.platform.api;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/** Records telemetry-source freshness without replacing failed snapshots with zero values. */
@Component
public final class MetricsSourceRecorder {

  private final MeterRegistry meters;
  private final Clock clock;
  private final Map<Source, State> states = new EnumMap<>(Source.class);

  public MetricsSourceRecorder(MeterRegistry meters, Clock clock) {
    this.meters = meters;
    this.clock = clock;
    for (Source source : Source.values()) {
      State state = new State();
      states.put(source, state);
      gauge("agd.metrics.source.sampled", source, state.sampled);
      gauge("agd.metrics.source.available", source, state.available);
    }
  }

  public void success(Source source, Timer.Sample sample) {
    State state = states.get(source);
    state.sampled.set(1);
    state.available.set(1);
    state.lastSuccess.set(clock.instant().getEpochSecond());
    if (!state.lastSuccessGaugeRegistered) {
      synchronized (state) {
        if (!state.lastSuccessGaugeRegistered) {
          gauge("agd.metrics.source.last_success.timestamp", source, state.lastSuccess);
          state.lastSuccessGaugeRegistered = true;
        }
      }
    }
    stop(sample, source, "success");
  }

  public void failure(Source source, Timer.Sample sample, RuntimeException failure) {
    State state = states.get(source);
    state.sampled.set(1);
    state.available.set(0);
    String result =
        failure.getClass().getSimpleName().toLowerCase().contains("timeout") ? "timeout" : "error";
    stop(sample, source, result);
    meters
        .counter("agd.metrics.source.collection.failures", "source", source.tag(), "result", result)
        .increment();
  }

  public Timer.Sample start() {
    return Timer.start(meters);
  }

  private void stop(Timer.Sample sample, Source source, String result) {
    sample.stop(
        Timer.builder("agd.metrics.source.collection.duration")
            .tag("source", source.tag())
            .tag("result", result)
            .register(meters));
  }

  private void gauge(String name, Source source, AtomicLong value) {
    Gauge.builder(name, value, AtomicLong::doubleValue)
        .tag("source", source.tag())
        .strongReference(true)
        .register(meters);
  }

  private static final class State {
    private final AtomicLong sampled = new AtomicLong();
    private final AtomicLong available = new AtomicLong();
    private final AtomicLong lastSuccess = new AtomicLong();
    private boolean lastSuccessGaugeRegistered;
  }

  public enum Source {
    OUTBOX("outbox"),
    NOTIFICATIONS("notifications"),
    SUBSCRIPTIONS("subscriptions"),
    EXPORTS("exports");

    private final String tag;

    Source(String tag) {
      this.tag = tag;
    }

    public String tag() {
      return tag;
    }
  }
}
