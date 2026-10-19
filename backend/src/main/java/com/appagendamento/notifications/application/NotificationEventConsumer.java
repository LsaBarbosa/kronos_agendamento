package com.appagendamento.notifications.application;

import com.appagendamento.appointments.api.AppointmentNotificationQuery;
import com.appagendamento.notifications.domain.NotificationType;
import com.appagendamento.outbox.api.OutboxEvent;
import com.appagendamento.outbox.api.OutboxEventFeed;
import com.appagendamento.outbox.application.IdempotentEventConsumer;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class NotificationEventConsumer {

  public static final String CONSUMER_NAME = "notifications.appointments.v1";
  private final OutboxEventFeed events;
  private final IdempotentEventConsumer processedEvents;
  private final AppointmentNotificationQuery appointments;
  private final NotificationRepository notifications;
  private final MeterRegistry meters;
  private final int batchSize;

  public NotificationEventConsumer(
      OutboxEventFeed events,
      IdempotentEventConsumer processedEvents,
      AppointmentNotificationQuery appointments,
      NotificationRepository notifications,
      MeterRegistry meters,
      @Value("${app.notifications.consumer.batch-size:100}") int batchSize) {
    if (batchSize < 1 || batchSize > 500) {
      throw new IllegalArgumentException("Tamanho do lote de eventos inválido");
    }
    this.events = events;
    this.processedEvents = processedEvents;
    this.appointments = appointments;
    this.notifications = notifications;
    this.meters = meters;
    this.batchSize = batchSize;
  }

  public int processBatch() {
    List<OutboxEvent> pending = events.unprocessedAppointmentEvents(CONSUMER_NAME, batchSize);
    int processed = 0;
    for (OutboxEvent event : pending) {
      if (processedEvents.process(CONSUMER_NAME, event.eventId(), () -> process(event))) {
        processed++;
        meters.counter("agd.notifications.events.processed").increment();
      }
    }
    return processed;
  }

  private void process(OutboxEvent event) {
    NotificationType type = NotificationType.fromEvent(event.eventType()).orElseThrow();
    var current = appointments.find(event.tenantId(), event.aggregateId());
    if (current.isEmpty()) {
      return;
    }
    var appointment = current.get();
    if (appointment.version() != event.aggregateVersion()
        || !type.matchesCurrent(appointment.status())) {
      return;
    }
    notifications.createIntent(
        event.tenantId(),
        event.aggregateId(),
        event.aggregateVersion(),
        appointment.clientId(),
        event.eventId(),
        type);
    meters.counter("agd.notifications.intents.created", "type", type.name()).increment();
  }
}
