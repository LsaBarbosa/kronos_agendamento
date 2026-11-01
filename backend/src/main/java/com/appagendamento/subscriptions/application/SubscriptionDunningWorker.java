package com.appagendamento.subscriptions.application;

import com.appagendamento.platform.api.DependencyMetrics;
import com.appagendamento.platform.api.DependencyMetrics.Dependency;
import com.appagendamento.platform.api.MetricsSourceRecorder;
import com.appagendamento.platform.api.MetricsSourceRecorder.Source;
import com.appagendamento.subscriptions.domain.DunningAttemptClaim;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SubscriptionDunningWorker {

  private static final Duration BASE_BACKOFF = Duration.ofSeconds(30);
  private static final Duration MAX_BACKOFF = Duration.ofMinutes(15);

  private final SubscriptionDunningRepository dunning;
  private final ObjectProvider<DunningChargeTransport> transports;
  private final ObjectProvider<DunningAttemptReconciler> reconcilers;
  private final Clock clock;
  private final Duration lease;
  private final int batchSize;
  private final int maxTechnicalAttempts;
  private final MeterRegistry meters;
  private final MetricsSourceRecorder sourceMetrics;
  private final DependencyMetrics dependencyMetrics;
  private final AtomicLong backlog;
  private final AtomicLong overdue;
  private final AtomicLong oldestOverdueSeconds;
  private final AtomicLong outcomeUnknown;
  private final AtomicLong acceptedTransport;
  private final AtomicLong declined;
  private final AtomicLong failedFinal;
  private final AtomicLong skippedLateRecovery;
  private final AtomicLong technicalAttempts;
  private final AtomicLong backlogCapped;

  public SubscriptionDunningWorker(
      SubscriptionDunningRepository dunning,
      ObjectProvider<DunningChargeTransport> transports,
      ObjectProvider<DunningAttemptReconciler> reconcilers,
      MeterRegistry meters,
      Clock clock,
      @Value("${app.subscriptions.dunning-worker.batch-size:20}") int batchSize,
      @Value("${app.subscriptions.dunning-worker.lease-seconds:60}") long leaseSeconds,
      @Value("${app.subscriptions.dunning-worker.max-technical-attempts:5}")
          int maxTechnicalAttempts) {
    this(
        dunning,
        transports,
        reconcilers,
        meters,
        clock,
        new MetricsSourceRecorder(meters, clock),
        null,
        batchSize,
        leaseSeconds,
        maxTechnicalAttempts);
  }

  @Autowired
  public SubscriptionDunningWorker(
      SubscriptionDunningRepository dunning,
      ObjectProvider<DunningChargeTransport> transports,
      ObjectProvider<DunningAttemptReconciler> reconcilers,
      MeterRegistry meters,
      Clock clock,
      MetricsSourceRecorder sourceMetrics,
      DependencyMetrics dependencyMetrics,
      @Value("${app.subscriptions.dunning-worker.batch-size:20}") int batchSize,
      @Value("${app.subscriptions.dunning-worker.lease-seconds:60}") long leaseSeconds,
      @Value("${app.subscriptions.dunning-worker.max-technical-attempts:5}")
          int maxTechnicalAttempts) {
    if (batchSize < 1 || batchSize > 100 || leaseSeconds < 5 || maxTechnicalAttempts < 1) {
      throw new IllegalArgumentException("Configuração do worker de inadimplência inválida");
    }
    this.dunning = dunning;
    this.transports = transports;
    this.reconcilers = reconcilers;
    this.meters = meters;
    this.sourceMetrics = sourceMetrics;
    this.dependencyMetrics = dependencyMetrics;
    if (dependencyMetrics != null) {
      dependencyMetrics.configured(
          Dependency.PAYMENT_PROVIDER, transports.getIfAvailable() != null);
      dependencyMetrics.configured(
          Dependency.PAYMENT_RECONCILER, reconcilers.getIfAvailable() != null);
    }
    this.clock = clock;
    this.batchSize = batchSize;
    this.lease = Duration.ofSeconds(leaseSeconds);
    this.maxTechnicalAttempts = maxTechnicalAttempts;
    this.backlog = taggedGauge("agd.workers.backlog.eligible", "worker", "subscription_dunning");
    this.overdue = taggedGauge("agd.workers.backlog.overdue", "worker", "subscription_dunning");
    this.oldestOverdueSeconds =
        taggedGauge("agd.workers.backlog.oldest_age", "worker", "subscription_dunning");
    this.outcomeUnknown =
        taggedGauge("agd.reconciliation.outcome_unknown", "subsystem", "payments");
    this.acceptedTransport = gauge("accepted_transport");
    this.declined = gauge("declined");
    this.failedFinal = gauge("failed_final");
    this.skippedLateRecovery = gauge("skipped_late_recovery");
    this.technicalAttempts = gauge("technical_attempts");
    this.backlogCapped =
        taggedGauge("agd.workers.backlog.capped", "worker", "subscription_dunning");
  }

  /** Processes at most one claimed attempt at a time; network calls run outside DB transactions. */
  public int processBatch() {
    int processed = processAvailableBatch();
    refreshMetrics();
    return processed;
  }

  private int processAvailableBatch() {
    int completed = 0;
    for (var reference : dunning.pendingPaymentEvidence(100)) {
      dunning.reconcilePaymentEvidence(
          reference.providerCode(), reference.accountId(), reference.reference());
    }
    dunning.materializeAnnualRenewalNotices(clock.instant(), batchSize);
    for (int index = 0; index < batchSize; index++) {
      DunningChargeTransport transport = transports.getIfAvailable();
      DunningAttemptReconciler reconciler = reconcilers.getIfAvailable();
      var claim = dunning.claimNextAttempt(lease, reconciler != null);
      if (claim.isEmpty()) break;
      DunningAttemptClaim work = claim.get();
      if (work.workType().equals("RECONCILE")) {
        processReconciliation(work, reconciler);
        completed++;
        continue;
      }
      if (transport == null) {
        meters.counter("agd.subscriptions.dunning.worker.disabled").increment();
        dunning.blockAttemptForConfiguration(work, "TRANSPORT_NOT_CONFIGURED");
        break;
      }
      if (!dunning.authorizeAttemptSend(work)) {
        break;
      }
      DunningChargeTransport.TransportResult result;
      try {
        result = transport.submit(toRequest(work));
        if (result == null) throw new IllegalStateException("Resposta vazia do transporte");
        if (dependencyMetrics != null) {
          dependencyMetrics.observed(Dependency.PAYMENT_PROVIDER, true);
        }
      } catch (RuntimeException ambiguous) {
        result = unknown("TRANSPORT_OUTCOME_UNKNOWN");
        if (dependencyMetrics != null) {
          dependencyMetrics.observed(Dependency.PAYMENT_PROVIDER, false);
        }
      }
      dunning.recordTransportResult(
          work, result, backoff(work.technicalAttempts() + 1), maxTechnicalAttempts);
      completed++;
    }
    return completed;
  }

  private AtomicLong gauge(String name) {
    return meters.gauge("agd.subscriptions.dunning." + name, new AtomicLong());
  }

  private AtomicLong taggedGauge(String name, String tagKey, String tagValue) {
    AtomicLong value = new AtomicLong();
    Gauge.builder(name, value, AtomicLong::doubleValue)
        .tag(tagKey, tagValue)
        .strongReference(true)
        .register(meters);
    return value;
  }

  private void refreshMetrics() {
    var sample = sourceMetrics.start();
    try {
      var snapshot = dunning.metricsSnapshot(clock.instant());
      backlog.set(snapshot.backlog());
      overdue.set(snapshot.overdue());
      oldestOverdueSeconds.set(snapshot.oldestOverdueSeconds());
      outcomeUnknown.set(snapshot.outcomeUnknown());
      acceptedTransport.set(snapshot.acceptedTransport());
      declined.set(snapshot.declined());
      failedFinal.set(snapshot.failedFinal());
      skippedLateRecovery.set(snapshot.skippedLateRecovery());
      technicalAttempts.set(snapshot.technicalAttempts());
      backlogCapped.set(snapshot.capped() ? 1 : 0);
      sourceMetrics.success(Source.SUBSCRIPTIONS, sample);
    } catch (RuntimeException failure) {
      sourceMetrics.failure(Source.SUBSCRIPTIONS, sample, failure);
      org.slf4j.LoggerFactory.getLogger(SubscriptionDunningWorker.class)
          .warn(
              "Metrics snapshot unavailable: source=subscriptions failureType={}",
              failure.getClass().getSimpleName());
    }
  }

  private void processReconciliation(
      DunningAttemptClaim claim, DunningAttemptReconciler reconciler) {
    DunningChargeTransport.TransportResult result;
    try {
      if (reconciler == null) return;
      result =
          reconciler.reconcile(
              claim.tenantId(),
              claim.attemptId(),
              claim.externalIdempotencyKey(),
              claim.providerReference());
      if (result == null) throw new IllegalStateException("Resposta de reconciliação vazia");
      if (dependencyMetrics != null) {
        dependencyMetrics.observed(Dependency.PAYMENT_RECONCILER, true);
      }
    } catch (RuntimeException unavailable) {
      result = unknown("RECONCILIATION_OUTCOME_UNKNOWN");
      if (dependencyMetrics != null) {
        dependencyMetrics.observed(Dependency.PAYMENT_RECONCILER, false);
      }
    }
    dunning.recordReconciliationResult(
        claim, result, backoff(claim.technicalAttempts() + 1), maxTechnicalAttempts);
  }

  private static DunningChargeTransport.ChargeRequest toRequest(DunningAttemptClaim claim) {
    return new DunningChargeTransport.ChargeRequest(
        claim.tenantId(),
        claim.subscriptionId(),
        claim.cycleId(),
        claim.attemptId(),
        claim.externalIdempotencyKey(),
        claim.logicalSequence(),
        claim.planCode(),
        claim.scheduledAt(),
        claim.amountMinorUnits(),
        claim.currency(),
        claim.termsVersion(),
        claim.offerVersion());
  }

  private static DunningChargeTransport.TransportResult unknown(String code) {
    return new DunningChargeTransport.TransportResult(
        DunningChargeTransport.Outcome.OUTCOME_UNKNOWN, null, null, code);
  }

  private static Duration backoff(int attemptNumber) {
    int shift = Math.min(Math.max(attemptNumber - 1, 0), 5);
    long seconds = Math.min(BASE_BACKOFF.toSeconds() << shift, MAX_BACKOFF.toSeconds());
    return Duration.ofSeconds(seconds);
  }
}
