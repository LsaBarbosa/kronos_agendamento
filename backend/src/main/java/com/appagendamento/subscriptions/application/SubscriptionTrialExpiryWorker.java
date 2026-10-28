package com.appagendamento.subscriptions.application;

import com.appagendamento.platform.api.WorkerMetrics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.subscriptions.trial-expiry-worker.enabled", havingValue = "true")
@EnableScheduling
public class SubscriptionTrialExpiryWorker {

  private final SubscriptionTrialLifecycle lifecycle;
  private final WorkerMetrics metrics;

  public SubscriptionTrialExpiryWorker(
      SubscriptionTrialLifecycle lifecycle, WorkerMetrics metrics) {
    this.lifecycle = lifecycle;
    this.metrics = metrics;
  }

  @Scheduled(fixedDelayString = "${app.subscriptions.trial-expiry-worker.poll-delay-ms:60000}")
  public void expireDueTrials() {
    metrics.execute(
        WorkerMetrics.Worker.TRIAL_EXPIRY, () -> lifecycle.materializeExpiredBatch(100));
  }
}
