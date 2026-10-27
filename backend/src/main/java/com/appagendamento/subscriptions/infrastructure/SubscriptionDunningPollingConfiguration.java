package com.appagendamento.subscriptions.infrastructure;

import com.appagendamento.platform.api.WorkerMetrics;
import com.appagendamento.subscriptions.application.SubscriptionDunningWorker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    name = "app.subscriptions.dunning-worker.enabled",
    havingValue = "true",
    matchIfMissing = true)
@EnableScheduling
public class SubscriptionDunningPollingConfiguration {

  private final SubscriptionDunningWorker worker;
  private final WorkerMetrics metrics;

  public SubscriptionDunningPollingConfiguration(
      SubscriptionDunningWorker worker, WorkerMetrics metrics) {
    this.worker = worker;
    this.metrics = metrics;
  }

  @Scheduled(fixedDelayString = "${app.subscriptions.dunning-worker.poll-delay-ms:60000}")
  void processDueAttempts() {
    metrics.execute(WorkerMetrics.Worker.SUBSCRIPTION_DUNNING, worker::processBatch);
  }
}
