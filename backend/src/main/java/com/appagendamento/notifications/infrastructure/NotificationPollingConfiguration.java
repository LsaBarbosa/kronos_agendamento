package com.appagendamento.notifications.infrastructure;

import com.appagendamento.notifications.application.NotificationEventConsumer;
import com.appagendamento.notifications.application.NotificationReminderEventConsumer;
import com.appagendamento.notifications.application.NotificationWorker;
import com.appagendamento.platform.api.WorkerMetrics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    name = "app.notifications.consumer.enabled",
    havingValue = "true",
    matchIfMissing = true)
@EnableScheduling
public class NotificationPollingConfiguration {

  private final NotificationEventConsumer eventConsumer;
  private final NotificationWorker worker;
  private final NotificationReminderEventConsumer reminderEventConsumer;
  private final WorkerMetrics metrics;

  public NotificationPollingConfiguration(
      NotificationEventConsumer eventConsumer,
      NotificationReminderEventConsumer reminderEventConsumer,
      NotificationWorker worker,
      WorkerMetrics metrics) {
    this.eventConsumer = eventConsumer;
    this.reminderEventConsumer = reminderEventConsumer;
    this.worker = worker;
    this.metrics = metrics;
  }

  @Scheduled(fixedDelayString = "${app.notifications.consumer.poll-delay-ms:1000}")
  public void consumeAppointmentEvents() {
    metrics.execute(WorkerMetrics.Worker.NOTIFICATIONS, eventConsumer::processBatch);
  }

  @Scheduled(fixedDelayString = "${app.notifications.reminders.consumer.poll-delay-ms:1000}")
  public void consumeAppointmentReminderEvents() {
    metrics.execute(WorkerMetrics.Worker.REMINDERS, reminderEventConsumer::processBatch);
  }

  @Scheduled(fixedDelayString = "${app.notifications.worker.poll-delay-ms:1000}")
  public void processNotifications() {
    metrics.execute(WorkerMetrics.Worker.NOTIFICATIONS, worker::processBatch);
  }
}
