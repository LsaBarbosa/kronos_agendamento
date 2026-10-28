package com.appagendamento.outbox.application;

import com.appagendamento.outbox.api.DeliveryResult;
import com.appagendamento.outbox.api.OutboxClaim;
import com.appagendamento.outbox.api.OutboxMetricsSnapshot;
import com.appagendamento.outbox.api.OutboxPublisher;
import com.appagendamento.outbox.api.OutboxStore;
import com.appagendamento.platform.api.DependencyMetrics;
import com.appagendamento.platform.api.DependencyMetrics.Dependency;
import com.appagendamento.platform.api.MetricsSourceRecorder;
import com.appagendamento.platform.api.MetricsSourceRecorder.Source;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class OutboxWorker {

  private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);
  private static final Duration BASE_BACKOFF = Duration.ofSeconds(1);
  private static final Duration MAX_BACKOFF = Duration.ofMinutes(15);
  private final OutboxStore store;
  private final ObjectProvider<OutboxPublisher> publishers;
  private final int batchSize;
  private final Duration leaseDuration;
  private final int maxAttempts;
  private final MeterRegistry meters;
  private final MetricsSourceRecorder sourceMetrics;
  private final AtomicLong backlog;
  private final AtomicLong oldestAgeSeconds;
  private final AtomicLong backlogCapped;
  private final AtomicLong reconciliationRequired;
  private final DependencyMetrics dependencyMetrics;

  public OutboxWorker(
      OutboxStore store,
      ObjectProvider<OutboxPublisher> publishers,
      @Value("${app.outbox.worker.batch-size:20}") int batchSize,
      @Value("${app.outbox.worker.lease-seconds:60}") long leaseSeconds,
      @Value("${app.outbox.worker.max-attempts:8}") int maxAttempts) {
    this(
        store,
        publishers,
        batchSize,
        leaseSeconds,
        maxAttempts,
        new SimpleMeterRegistry(),
        Clock.systemUTC(),
        null,
        null);
  }

  @Autowired
  public OutboxWorker(
      OutboxStore store,
      ObjectProvider<OutboxPublisher> publishers,
      @Value("${app.outbox.worker.batch-size:20}") int batchSize,
      @Value("${app.outbox.worker.lease-seconds:60}") long leaseSeconds,
      @Value("${app.outbox.worker.max-attempts:8}") int maxAttempts,
      MeterRegistry meters,
      Clock clock,
      MetricsSourceRecorder sourceMetrics,
      DependencyMetrics dependencyMetrics) {
    if (batchSize < 1 || leaseSeconds < 1 || maxAttempts < 1) {
      throw new IllegalArgumentException("Configuração do worker outbox inválida");
    }
    this.store = store;
    this.publishers = publishers;
    this.batchSize = batchSize;
    this.leaseDuration = Duration.ofSeconds(leaseSeconds);
    this.maxAttempts = maxAttempts;
    this.meters = meters;
    this.sourceMetrics =
        sourceMetrics == null ? new MetricsSourceRecorder(meters, clock) : sourceMetrics;
    this.dependencyMetrics = dependencyMetrics;
    if (dependencyMetrics != null) {
      dependencyMetrics.configured(
          Dependency.OUTBOX_DESTINATION, publishers != null && publishers.getIfAvailable() != null);
    }
    this.backlog = taggedGauge("agd.workers.backlog.eligible", "worker", "outbox");
    this.oldestAgeSeconds = taggedGauge("agd.workers.backlog.oldest_age", "worker", "outbox");
    this.backlogCapped = taggedGauge("agd.workers.backlog.capped", "worker", "outbox");
    this.reconciliationRequired = taggedGauge("agd.reconciliation.pending", "subsystem", "outbox");
  }

  public int processBatch() {
    OutboxPublisher publisher = publishers.getIfAvailable();
    int processed = publisher == null ? 0 : processBatch(publisher);
    refreshMetrics();
    return processed;
  }

  public int processBatch(OutboxPublisher publisher) {
    int processed = 0;
    while (processed < batchSize) {
      List<OutboxClaim> claims = store.claim(1, leaseDuration);
      if (claims.isEmpty()) {
        break;
      }
      OutboxClaim claim = claims.getFirst();
      processed++;
      DeliveryResult result;
      try {
        result = publisher.publish(claim);
        if (dependencyMetrics != null) {
          dependencyMetrics.observed(Dependency.OUTBOX_DESTINATION, true);
        }
      } catch (RuntimeException failure) {
        result = DeliveryResult.OUTCOME_UNKNOWN;
        if (dependencyMetrics != null) {
          dependencyMetrics.observed(Dependency.OUTBOX_DESTINATION, false);
        }
      }
      boolean recorded =
          switch (result) {
            case DELIVERED -> store.markDelivered(claim);
            case RETRYABLE_FAILURE ->
                store.markRetry(
                    claim, backoff(claim.attemptCount()), maxAttempts, "DESTINATION_RETRYABLE");
            case TERMINAL_FAILURE -> store.markTerminal(claim, "DESTINATION_TERMINAL");
            case OUTCOME_UNKNOWN -> store.markOutcomeUnknown(claim, "DESTINATION_OUTCOME_UNKNOWN");
          };
      if (!recorded) {
        log.warn(
            "Outbox claim transition rejected: eventId={}, outcome={}", claim.eventId(), result);
        break;
      }
    }
    return processed;
  }

  private void refreshMetrics() {
    var sample = sourceMetrics.start();
    try {
      OutboxMetricsSnapshot snapshot =
          store
              .metricsSnapshot()
              .orElseThrow(() -> new IllegalStateException("Snapshot indisponível"));
      backlog.set(snapshot.eligibleCount());
      oldestAgeSeconds.set(snapshot.oldestEligibleAgeSeconds());
      backlogCapped.set(snapshot.capped() ? 1 : 0);
      reconciliationRequired.set(snapshot.reconciliationRequiredCount());
      sourceMetrics.success(Source.OUTBOX, sample);
    } catch (RuntimeException failure) {
      sourceMetrics.failure(Source.OUTBOX, sample, failure);
      log.warn(
          "Metrics snapshot unavailable: source=outbox failureType={}",
          failure.getClass().getSimpleName());
    }
  }

  private AtomicLong taggedGauge(String name, String tagKey, String tagValue) {
    AtomicLong value = new AtomicLong();
    Gauge.builder(name, value, AtomicLong::doubleValue)
        .tag(tagKey, tagValue)
        .strongReference(true)
        .register(meters);
    return value;
  }

  public boolean reprocess(UUID eventId) {
    return store.reprocess(eventId);
  }

  private static Duration backoff(int attempt) {
    long multiplier = 1L << Math.min(Math.max(attempt - 1, 0), 20);
    Duration calculated = BASE_BACKOFF.multipliedBy(multiplier);
    return calculated.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : calculated;
  }
}
