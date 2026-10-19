package com.appagendamento.notifications.application;

import com.appagendamento.appointments.api.AppointmentNotificationQuery;
import com.appagendamento.availability.api.ReminderOffsetQuery;
import com.appagendamento.notifications.domain.ReminderSchedule;
import com.appagendamento.outbox.api.OutboxEvent;
import com.appagendamento.outbox.api.OutboxEventFeed;
import com.appagendamento.outbox.application.IdempotentEventConsumer;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class NotificationReminderEventConsumer {

  public static final String CONSUMER_NAME = "notifications.appointment-reminders.v1";
  private static final Set<String> CONFIRMING_EVENTS =
      Set.of("AppointmentConfirmed", "AppointmentRescheduled");
  private static final Set<String> TERMINAL_EVENTS =
      Set.of("AppointmentCancelled", "AppointmentCompleted", "ClientNoShowRecorded");

  private final OutboxEventFeed events;
  private final IdempotentEventConsumer processedEvents;
  private final AppointmentNotificationQuery appointments;
  private final ReminderOffsetQuery reminderOffsets;
  private final NotificationRepository notifications;
  private final MeterRegistry meters;
  private final int batchSize;

  public NotificationReminderEventConsumer(
      OutboxEventFeed events,
      IdempotentEventConsumer processedEvents,
      AppointmentNotificationQuery appointments,
      ReminderOffsetQuery reminderOffsets,
      NotificationRepository notifications,
      MeterRegistry meters,
      @Value("${app.notifications.reminders.consumer.batch-size:100}") int batchSize) {
    if (batchSize < 1 || batchSize > 500) {
      throw new IllegalArgumentException("Tamanho do lote de lembretes inválido");
    }
    this.events = events;
    this.processedEvents = processedEvents;
    this.appointments = appointments;
    this.reminderOffsets = reminderOffsets;
    this.notifications = notifications;
    this.meters = meters;
    this.batchSize = batchSize;
  }

  public int processBatch() {
    List<OutboxEvent> pending =
        events.unprocessedAppointmentReminderEvents(CONSUMER_NAME, batchSize);
    int processed = 0;
    for (OutboxEvent event : pending) {
      if (processedEvents.process(CONSUMER_NAME, event.eventId(), () -> process(event))) {
        processed++;
        meters.counter("agd.notifications.reminders.events.processed").increment();
      }
    }
    return processed;
  }

  private void process(OutboxEvent event) {
    if (!CONFIRMING_EVENTS.contains(event.eventType())
        && !TERMINAL_EVENTS.contains(event.eventType())) {
      throw new IllegalArgumentException("Evento de reserva não suportado por lembrete");
    }
    var current = appointments.find(event.tenantId(), event.aggregateId());
    if (current.isEmpty()) {
      return;
    }
    var appointment = current.get();
    if (!"CONFIRMED".equals(appointment.status())) {
      int invalidated = notifications.obsoleteAllReminders(event.tenantId(), event.aggregateId());
      incrementObsolete("terminal", invalidated);
      return;
    }
    if (appointment.version() > event.aggregateVersion()) {
      int invalidated =
          notifications.obsoleteRemindersBeforeVersion(
              event.tenantId(), event.aggregateId(), appointment.version());
      incrementObsolete("superseded", invalidated);
      return;
    }
    if (appointment.version() != event.aggregateVersion()
        || !CONFIRMING_EVENTS.contains(event.eventType())) {
      return;
    }

    int invalidated =
        notifications.obsoleteRemindersBeforeVersion(
            event.tenantId(), event.aggregateId(), appointment.version());
    incrementObsolete("superseded", invalidated);
    var offset = reminderOffsets.findReminderOffsetHours(event.tenantId());
    if (offset.isEmpty()) {
      meters.counter("agd.notifications.reminders.skipped", "reason", "policy_absent").increment();
      return;
    }
    Instant dueAt = ReminderSchedule.dueAt(appointment.startsAt(), offset.getAsInt());
    notifications.createReminderIntent(
        event.tenantId(),
        event.aggregateId(),
        event.aggregateVersion(),
        appointment.clientId(),
        event.eventId(),
        dueAt);
    meters.counter("agd.notifications.reminders.scheduled").increment();
  }

  private void incrementObsolete(String reason, int count) {
    if (count > 0) {
      meters.counter("agd.notifications.reminders.obsoleted", "reason", reason).increment(count);
    }
  }
}
