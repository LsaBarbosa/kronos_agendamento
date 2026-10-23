package com.appagendamento.exports.infrastructure;

import com.appagendamento.exports.application.ExportWorker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.exports.worker.enabled", havingValue = "true")
@EnableScheduling
public class ExportPollingConfiguration {

  private final ExportWorker worker;

  public ExportPollingConfiguration(ExportWorker worker) {
    this.worker = worker;
  }

  @Scheduled(fixedDelayString = "${app.exports.worker.poll-delay-ms:1000}")
  void processOne() {
    worker.processOne();
    worker.expireAndClean();
  }
}
