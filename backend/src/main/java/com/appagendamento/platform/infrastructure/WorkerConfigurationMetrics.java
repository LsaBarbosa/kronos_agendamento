package com.appagendamento.platform.infrastructure;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class WorkerConfigurationMetrics implements ApplicationRunner {

  private final MeterRegistry meters;
  private final Environment environment;

  public WorkerConfigurationMetrics(MeterRegistry meters, Environment environment) {
    this.meters = meters;
    this.environment = environment;
  }

  @Override
  public void run(ApplicationArguments arguments) {
    List<WorkerProperty> workers =
        List.of(
            new WorkerProperty("outbox", "app.outbox.worker.enabled", false),
            new WorkerProperty("reminders", "app.notifications.consumer.enabled", true),
            new WorkerProperty("notifications", "app.notifications.consumer.enabled", true),
            new WorkerProperty(
                "subscription_dunning", "app.subscriptions.dunning-worker.enabled", true),
            new WorkerProperty(
                "trial_expiry", "app.subscriptions.trial-expiry-worker.enabled", true),
            new WorkerProperty("exports", "app.exports.worker.enabled", false));
    for (WorkerProperty worker : workers) {
      boolean configured = environment.containsProperty(worker.property());
      boolean enabled =
          environment.getProperty(worker.property(), Boolean.class, worker.defaultEnabled());
      gauge(
          "agd.worker.configuration",
          "worker",
          worker.name(),
          "state",
          enabled ? "enabled" : "disabled",
          1);
      gauge(
          "agd.worker.configuration",
          "worker",
          worker.name(),
          "state",
          enabled ? "disabled" : "enabled",
          0);
      gauge("agd.worker.configuration.present", "worker", worker.name(), configured ? 1 : 0);
    }
  }

  private void gauge(String name, String key, String value, long sample) {
    AtomicLong state = new AtomicLong(sample);
    Gauge.builder(name, state, AtomicLong::doubleValue)
        .tag(key, value)
        .strongReference(true)
        .register(meters);
  }

  private void gauge(
      String name,
      String firstKey,
      String firstValue,
      String secondKey,
      String secondValue,
      long sample) {
    AtomicLong state = new AtomicLong(sample);
    Gauge.builder(name, state, AtomicLong::doubleValue)
        .tag(firstKey, firstValue)
        .tag(secondKey, secondValue)
        .strongReference(true)
        .register(meters);
  }

  private record WorkerProperty(String name, String property, boolean defaultEnabled) {}
}
