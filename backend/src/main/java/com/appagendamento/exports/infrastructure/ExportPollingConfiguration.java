package com.appagendamento.exports.infrastructure;

import com.appagendamento.exports.application.ExportArtifactStore;
import com.appagendamento.exports.application.ExportMetricsSnapshot;
import com.appagendamento.exports.application.ExportWorker;
import com.appagendamento.exports.application.ExportWorkerRepository;
import com.appagendamento.platform.api.DependencyMetrics;
import com.appagendamento.platform.api.DependencyMetrics.Dependency;
import com.appagendamento.platform.api.MetricsSourceRecorder;
import com.appagendamento.platform.api.MetricsSourceRecorder.Source;
import com.appagendamento.platform.api.WorkerMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.exports.worker.enabled", havingValue = "true")
@EnableScheduling
public class ExportPollingConfiguration {

  private final ExportWorker worker;
  private final WorkerMetrics metrics;
  private final ExportWorkerRepository repository;
  private final MetricsSourceRecorder sourceMetrics;
  private final Clock clock;
  private final AtomicLong backlog;
  private final AtomicLong oldestAge;
  private final AtomicLong capped;

  public ExportPollingConfiguration(
      ExportWorker worker,
      WorkerMetrics metrics,
      ExportWorkerRepository repository,
      MetricsSourceRecorder sourceMetrics,
      MeterRegistry meters,
      Clock clock,
      ObjectProvider<ExportArtifactStore> artifactStores,
      DependencyMetrics dependencyMetrics) {
    this.worker = worker;
    this.metrics = metrics;
    this.repository = repository;
    this.sourceMetrics = sourceMetrics;
    this.clock = clock;
    ExportArtifactStore store = artifactStores.getIfAvailable();
    dependencyMetrics.configured(Dependency.EXPORT_STORAGE, store != null && store.configured());
    this.backlog = gauge(meters, "agd.workers.backlog.eligible", "exports");
    this.oldestAge = gauge(meters, "agd.workers.backlog.oldest_age", "exports");
    this.capped = gauge(meters, "agd.workers.backlog.capped", "exports");
  }

  @Scheduled(fixedDelayString = "${app.exports.worker.poll-delay-ms:1000}")
  void processOne() {
    metrics.execute(
        WorkerMetrics.Worker.EXPORTS,
        () -> {
          boolean processed = worker.processOne();
          worker.expireAndClean();
          refreshMetrics();
          return processed ? 1 : 0;
        });
  }

  private void refreshMetrics() {
    var sample = sourceMetrics.start();
    try {
      ExportMetricsSnapshot snapshot =
          repository
              .metricsSnapshot(clock.instant())
              .orElseThrow(() -> new IllegalStateException("Snapshot indisponível"));
      backlog.set(snapshot.eligibleCount());
      oldestAge.set(snapshot.oldestEligibleAgeSeconds());
      capped.set(snapshot.capped() ? 1 : 0);
      sourceMetrics.success(Source.EXPORTS, sample);
    } catch (RuntimeException failure) {
      sourceMetrics.failure(Source.EXPORTS, sample, failure);
    }
  }

  private static AtomicLong gauge(MeterRegistry meters, String name, String worker) {
    AtomicLong value = new AtomicLong();
    Gauge.builder(name, value, AtomicLong::doubleValue)
        .tag("worker", worker)
        .strongReference(true)
        .register(meters);
    return value;
  }
}
