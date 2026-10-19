package com.appagendamento.appointments.api;

import com.appagendamento.appointments.application.ClientAppointmentView;
import java.time.Instant;
import java.util.UUID;

public record ClientAppointmentResponse(
    UUID appointmentId,
    String serviceName,
    Instant startsAt,
    Instant serviceEndsAt,
    String timeZone,
    String status,
    long version) {

  static ClientAppointmentResponse from(ClientAppointmentView view) {
    return new ClientAppointmentResponse(
        view.appointmentId(),
        view.serviceName(),
        view.startsAt(),
        view.serviceEndsAt(),
        view.timeZone(),
        view.status(),
        view.version());
  }
}
