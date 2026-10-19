package com.appagendamento.notifications.application;

import com.appagendamento.appointments.api.AppointmentNotificationQuery;
import com.appagendamento.clients.api.ClientNotificationContactQuery;
import com.appagendamento.notifications.application.NotificationRepository.NotificationClaim;
import com.appagendamento.notifications.domain.ReminderSchedule;
import com.appagendamento.notifications.domain.TransportFailure;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class NotificationWorker {

  private static final org.slf4j.Logger LOGGER =
      org.slf4j.LoggerFactory.getLogger(NotificationWorker.class);
  private static final Duration BASE_BACKOFF = Duration.ofSeconds(1);
  private static final Duration MAX_BACKOFF = Duration.ofMinutes(15);
  private final NotificationRepository notifications;
  private final AppointmentNotificationQuery appointments;
  private final ClientNotificationContactQuery contacts;
  private final ObjectProvider<NotificationTransport> transports;
  private final int batchSize;
  private final Duration lease;
  private final int maxAttempts;
  private final MeterRegistry meters;
  private final Clock clock;
  private final AtomicLong reminderBacklog;
  private final AtomicLong reminderOverdue;
  private final AtomicLong reminderOldestOverdueSeconds;
  private final AtomicLong reminderOutcomeUnknown;

  public NotificationWorker(
      NotificationRepository notifications,
      AppointmentNotificationQuery appointments,
      ClientNotificationContactQuery contacts,
      ObjectProvider<NotificationTransport> transports,
      MeterRegistry meters,
      Clock clock,
      @Value("${app.notifications.worker.batch-size:20}") int batchSize,
      @Value("${app.notifications.worker.lease-seconds:60}") long leaseSeconds,
      @Value("${app.notifications.worker.max-attempts:8}") int maxAttempts) {
    if (batchSize < 1 || leaseSeconds < 1 || maxAttempts < 1) {
      throw new IllegalArgumentException("Configuração do worker de notificação inválida");
    }
    this.notifications = notifications;
    this.appointments = appointments;
    this.contacts = contacts;
    this.transports = transports;
    this.meters = meters;
    this.batchSize = batchSize;
    this.lease = Duration.ofSeconds(leaseSeconds);
    this.maxAttempts = maxAttempts;
    this.clock = clock;
    this.reminderBacklog = meters.gauge("agd.notifications.reminders.backlog", new AtomicLong());
    this.reminderOverdue = meters.gauge("agd.notifications.reminders.overdue", new AtomicLong());
    this.reminderOldestOverdueSeconds =
        meters.gauge("agd.notifications.reminders.oldest_overdue_seconds", new AtomicLong());
    this.reminderOutcomeUnknown =
        meters.gauge("agd.notifications.reminders.outcome_unknown", new AtomicLong());
  }

  public int processBatch() {
    NotificationTransport transport = transports.getIfAvailable();
    if (transport == null) {
      meters.counter("agd.notifications.worker.disabled").increment();
      refreshReminderMetrics();
      return 0;
    }
    int processed = processBatch(transport);
    refreshReminderMetrics();
    return processed;
  }

  public int processBatch(NotificationTransport transport) {
    int processed = 0;
    while (processed < batchSize) {
      List<NotificationClaim> next = notifications.claimDue(1, lease);
      if (next.isEmpty()) {
        break;
      }
      NotificationClaim claim = next.getFirst();
      processed++;
      process(claim, transport);
    }
    return processed;
  }

  private void process(NotificationClaim claim, NotificationTransport transport) {
    com.appagendamento.appointments.api.AppointmentNotificationQuery.NotificationAppointment
        snapshot;
    String phone;
    java.util.Optional<
            com.appagendamento.appointments.api.AppointmentNotificationQuery
                .NotificationAppointment>
        currentAppointment;
    try {
      currentAppointment = appointments.find(claim.tenantId(), claim.appointmentId());
    } catch (RuntimeException failure) {
      logPreflightFailure(claim, failure);
      notifications.markRetryable(
          claim, backoff(claim.attemptNumber()), maxAttempts, "RECIPIENT_UNAVAILABLE");
      return;
    }
    if (currentAppointment.isEmpty()
        || currentAppointment.get().version() != claim.appointmentVersion()
        || !claim.type().matchesCurrent(currentAppointment.get().status())) {
      obsolete(claim);
      return;
    }
    snapshot = currentAppointment.get();
    if (claim.type()
            == com.appagendamento.notifications.domain.NotificationType.APPOINTMENT_REMINDER
        && !ReminderSchedule.beforeAppointmentStart(snapshot.startsAt(), clock)) {
      obsolete(claim);
      return;
    }

    java.util.Optional<String> recipient;
    try {
      recipient = contacts.findE164Phone(claim.tenantId(), claim.recipientClientId());
    } catch (RuntimeException failure) {
      logPreflightFailure(claim, failure);
      notifications.markRetryable(
          claim, backoff(claim.attemptNumber()), maxAttempts, "RECIPIENT_UNAVAILABLE");
      return;
    }
    if (recipient.isEmpty()) {
      notifications.markFailed(claim, "RECIPIENT_UNAVAILABLE");
      meters.counter("agd.notifications.attempts", "outcome", "failed").increment();
      return;
    }
    phone = recipient.get();
    try {
      currentAppointment = appointments.find(claim.tenantId(), claim.appointmentId());
    } catch (RuntimeException failure) {
      logPreflightFailure(claim, failure);
      notifications.markRetryable(
          claim, backoff(claim.attemptNumber()), maxAttempts, "RECIPIENT_UNAVAILABLE");
      return;
    }
    if (currentAppointment.isEmpty()
        || currentAppointment.get().version() != claim.appointmentVersion()
        || !claim.type().matchesCurrent(currentAppointment.get().status())
        || (claim.type()
                == com.appagendamento.notifications.domain.NotificationType.APPOINTMENT_REMINDER
            && !ReminderSchedule.beforeAppointmentStart(
                currentAppointment.get().startsAt(), clock))) {
      obsolete(claim);
      return;
    }
    snapshot = currentAppointment.get();
    if (!notifications.authorizeSend(claim, clock.instant())) {
      notifications.markObsolete(claim);
      meters.counter("agd.notifications.attempts", "outcome", "claim_not_current").increment();
      return;
    }
    try {
      NotificationTransport.TransportResult result =
          transport.send(
              new NotificationTransport.NotificationMessage(
                  claim.notificationId(),
                  claim.tenantId(),
                  claim.appointmentId(),
                  claim.appointmentVersion(),
                  claim.type(),
                  phone,
                  snapshot.clientName(),
                  snapshot.serviceName(),
                  snapshot.startsAt(),
                  snapshot.serviceEndsAt(),
                  snapshot.timeZone(),
                  claim.externalIdempotencyKey()));
      if (result.accepted()) {
        notifications.markAccepted(claim, result.providerCode(), result.providerReference());
        meters.counter("agd.notifications.attempts", "outcome", "accepted").increment();
        reminderOutcome(claim, "accepted");
      } else {
        applyFailure(claim, result.failure());
        meters
            .counter("agd.notifications.attempts", "outcome", result.failure().name().toLowerCase())
            .increment();
      }
    } catch (RuntimeException failure) {
      notifications.markUnknown(claim, "TRANSPORT_OUTCOME_UNKNOWN");
      meters.counter("agd.notifications.attempts", "outcome", "unknown").increment();
      reminderOutcome(claim, "unknown");
    }
  }

  private static void logPreflightFailure(NotificationClaim claim, RuntimeException failure) {
    LOGGER.warn(
        "Notification preflight unavailable: notificationId={} failureType={}",
        claim.notificationId(),
        failure.getClass().getSimpleName());
  }

  private void applyFailure(NotificationClaim claim, TransportFailure failure) {
    switch (failure) {
      case RETRYABLE_NO_EFFECT ->
          notifications.markRetryable(
              claim, backoff(claim.attemptNumber()), maxAttempts, failure.safeCode());
      case DEFINITIVE -> notifications.markFailed(claim, failure.safeCode());
      case AMBIGUOUS -> notifications.markUnknown(claim, failure.safeCode());
    }
    reminderOutcome(claim, failure.name().toLowerCase());
  }

  private void obsolete(NotificationClaim claim) {
    notifications.markObsolete(claim);
    meters.counter("agd.notifications.attempts", "outcome", "obsolete").increment();
    reminderOutcome(claim, "obsolete");
  }

  private void reminderOutcome(NotificationClaim claim, String outcome) {
    if (claim.type()
        == com.appagendamento.notifications.domain.NotificationType.APPOINTMENT_REMINDER) {
      meters.counter("agd.notifications.reminders.outcomes", "outcome", outcome).increment();
    }
  }

  private void refreshReminderMetrics() {
    NotificationRepository.ReminderBacklog snapshot = notifications.reminderBacklog();
    reminderBacklog.set(snapshot.pendingCount());
    reminderOverdue.set(snapshot.overdueCount());
    reminderOldestOverdueSeconds.set(snapshot.oldestOverdueSeconds());
    reminderOutcomeUnknown.set(snapshot.outcomeUnknownCount());
  }

  private static Duration backoff(int attempt) {
    long multiplier = 1L << Math.min(Math.max(attempt - 1, 0), 20);
    Duration delay = BASE_BACKOFF.multipliedBy(multiplier);
    return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
  }
}
