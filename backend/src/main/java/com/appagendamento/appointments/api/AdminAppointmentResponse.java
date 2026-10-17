package com.appagendamento.appointments.api;

import com.appagendamento.appointments.application.AdminAppointmentView;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

public record AdminAppointmentResponse(
    UUID appointmentId,
    UUID serviceId,
    String clientName,
    String serviceName,
    OffsetDateTime startsAt,
    OffsetDateTime serviceEndsAt,
    OffsetDateTime occupiedUntil,
    String timeZone,
    int durationMinutes,
    int bufferAfterMinutes,
    String status,
    OffsetDateTime createdAt,
    long version) {

  public static AdminAppointmentResponse from(AdminAppointmentView view) {
    ZoneId zone = ZoneId.of(view.timeZone());
    return new AdminAppointmentResponse(
        view.appointmentId(),
        view.serviceId(),
        view.clientName(),
        view.serviceName(),
        view.startsAt().atZone(zone).toOffsetDateTime(),
        view.serviceEndsAt().atZone(zone).toOffsetDateTime(),
        view.occupiedUntil().atZone(zone).toOffsetDateTime(),
        view.timeZone(),
        view.durationMinutes(),
        view.bufferAfterMinutes(),
        view.status().name(),
        view.createdAt().atZone(zone).toOffsetDateTime(),
        view.version());
  }
}
