package com.appagendamento.platform.infrastructure;

import com.appagendamento.platform.api.WorkerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class MicrometerWorkerMetrics implements WorkerMetrics {

  private final MeterRegistry registry;
  private final Clock clock;
  private final Map<Worker, WorkerState> states = new EnumMap<>(Worker.class);

  public MicrometerWorkerMetrics(MeterRegistry registry, Clock clock) {
    this.registry = registry;
    this.clock = clock;
    for (Worker worker : Worker.values()) states.put(worker, new WorkerState());
  }

  @Override
  public int execute(Worker worker, java.util.function.IntSupplier work) {
    Timer.Sample sample;
    try {
      sample = Timer.start(registry);
    } catch (RuntimeException ignored) {
      sample = null;
    }
    WorkerState state = states.get(worker);
    try {
      int processed = work.getAsInt();
      safeRecord(worker, state, processed == 0 ? "no_work" : "completed");
      if (processed > 0) safeProgress(worker, state, processed);
      return processed;
    } catch (RuntimeException failure) {
      safeRecord(worker, state, "failed");
      throw failure;
    } finally {
      if (sample != null) {
        try {
          sample.stop(
              Timer.builder("agd.worker.execution.duration")
                  .tag("worker", worker.tag())
                  .register(registry));
        } catch (RuntimeException ignored) {
          // Telemetry must not change worker outcomes.
        }
      }
    }
  }

  private void safeRecord(Worker worker, WorkerState state, String result) {
    try {
      record(worker, state, result);
    } catch (RuntimeException ignored) {
      // Telemetry must not change worker outcomes.
    }
  }

  private void safeProgress(Worker worker, WorkerState state, int processed) {
    try {
      registry.counter("agd.worker.items.processed", "worker", worker.tag()).increment(processed);
      state.progressTimestamp.set(clock.instant().getEpochSecond());
      state.registerProgressGauge(registry, worker);
    } catch (RuntimeException ignored) {
      // Telemetry must not change worker outcomes.
    }
  }

  private void record(Worker worker, WorkerState state, String result) {
    long timestamp = clock.instant().getEpochSecond();
    registry.counter("agd.worker.executions", "worker", worker.tag(), "result", result).increment();
    state.heartbeatTimestamp.set(timestamp);
    state.registerGauges(registry, worker);
  }

  private static final class WorkerState {
    private final AtomicLong heartbeatTimestamp = new AtomicLong();
    private final AtomicLong progressTimestamp = new AtomicLong();
    private boolean heartbeatGaugeRegistered;
    private boolean progressGaugeRegistered;

    private synchronized void registerGauges(MeterRegistry registry, Worker worker) {
      if (heartbeatGaugeRegistered) return;
      GaugeSupport.registerTimestamp(
          registry, "agd.worker.heartbeat.timestamp", worker, heartbeatTimestamp);
      heartbeatGaugeRegistered = true;
    }

    private synchronized void registerProgressGauge(MeterRegistry registry, Worker worker) {
      if (progressGaugeRegistered) return;
      GaugeSupport.registerTimestamp(
          registry, "agd.worker.progress.timestamp", worker, progressTimestamp);
      progressGaugeRegistered = true;
    }
  }

  private static final class GaugeSupport {
    private static void registerTimestamp(
        MeterRegistry registry, String name, Worker worker, AtomicLong timestamp) {
      io.micrometer.core.instrument.Gauge.builder(name, timestamp, AtomicLong::doubleValue)
          .tag("worker", worker.tag())
          .strongReference(true)
          .register(registry);
    }
  }
}
