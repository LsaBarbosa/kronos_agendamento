package com.appagendamento.subscriptions.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.subscriptions.trial-expiry-worker.enabled", havingValue = "true")
@EnableScheduling
public class SubscriptionTrialExpiryWorker {

  private final SubscriptionTrialLifecycle lifecycle;

  public SubscriptionTrialExpiryWorker(SubscriptionTrialLifecycle lifecycle) {
    this.lifecycle = lifecycle;
  }

  @Scheduled(fixedDelayString = "${app.subscriptions.trial-expiry-worker.poll-delay-ms:60000}")
  public void expireDueTrials() {
    lifecycle.materializeExpiredBatch(100);
  }
}
