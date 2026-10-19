package com.appagendamento.appointments.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AppointmentNotificationQuery {

  Optional<NotificationAppointment> find(UUID tenantId, UUID appointmentId);

  record NotificationAppointment(
      UUID tenantId,
      UUID appointmentId,
      UUID clientId,
      long version,
      String status,
      String clientName,
      String serviceName,
      Instant startsAt,
      Instant serviceEndsAt,
      String timeZone) {}
}
