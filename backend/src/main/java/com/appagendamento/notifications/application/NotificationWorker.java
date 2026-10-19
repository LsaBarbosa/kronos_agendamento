package com.appagendamento.notifications.application;

import com.appagendamento.appointments.api.AppointmentNotificationQuery;
import com.appagendamento.clients.api.ClientNotificationContactQuery;
import com.appagendamento.notifications.application.NotificationRepository.NotificationClaim;
import com.appagendamento.notifications.domain.TransportFailure;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
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

  public NotificationWorker(
      NotificationRepository notifications,
      AppointmentNotificationQuery appointments,
      ClientNotificationContactQuery contacts,
      ObjectProvider<NotificationTransport> transports,
      MeterRegistry meters,
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
  }

  public int processBatch() {
    NotificationTransport transport = transports.getIfAvailable();
    if (transport == null) {
      meters.counter("agd.notifications.worker.disabled").increment();
      return 0;
    }
    return processBatch(transport);
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
      notifications.markObsolete(claim);
      meters.counter("agd.notifications.attempts", "outcome", "obsolete").increment();
      return;
    }
    snapshot = currentAppointment.get();

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
      } else {
        applyFailure(claim, result.failure());
        meters
            .counter("agd.notifications.attempts", "outcome", result.failure().name().toLowerCase())
            .increment();
      }
    } catch (RuntimeException failure) {
      notifications.markUnknown(claim, "TRANSPORT_OUTCOME_UNKNOWN");
      meters.counter("agd.notifications.attempts", "outcome", "unknown").increment();
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
  }

  private static Duration backoff(int attempt) {
    long multiplier = 1L << Math.min(Math.max(attempt - 1, 0), 20);
    Duration delay = BASE_BACKOFF.multipliedBy(multiplier);
    return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
  }
}
