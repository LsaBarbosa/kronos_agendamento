package com.appagendamento.outbox.infrastructure;

import com.appagendamento.outbox.api.OutboxPublisher;
import com.appagendamento.outbox.application.OutboxWorker;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.outbox.worker.enabled", havingValue = "true")
@EnableScheduling
public class OutboxPollingConfiguration {

  private final OutboxWorker worker;

  public OutboxPollingConfiguration(
      OutboxWorker worker, ObjectProvider<OutboxPublisher> publishers) {
    if (publishers.getIfAvailable() == null) {
      throw new IllegalStateException(
          "OUTBOX_WORKER_ENABLED requer um destino OutboxPublisher configurado");
    }
    this.worker = worker;
  }

  @Scheduled(fixedDelayString = "${app.outbox.worker.poll-delay-ms:1000}")
  void poll() {
    worker.processBatch();
  }
}
