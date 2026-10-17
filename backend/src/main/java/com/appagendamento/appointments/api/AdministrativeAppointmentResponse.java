package com.appagendamento.appointments.api;

import com.appagendamento.appointments.application.AdministrativeAppointmentReceipt;
import com.appagendamento.appointments.domain.AppointmentStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AdministrativeAppointmentResponse(
    UUID appointmentId,
    UUID operationId,
    AppointmentStatus status,
    String serviceName,
    OffsetDateTime startsAt,
    OffsetDateTime serviceEndsAt,
    OffsetDateTime occupiedUntil,
    String timeZone) {

  public static AdministrativeAppointmentResponse from(AdministrativeAppointmentReceipt value) {
    return new AdministrativeAppointmentResponse(
        value.appointmentId(),
        value.operationId(),
        value.status(),
        value.serviceName(),
        value.startsAt(),
        value.serviceEndsAt(),
        value.occupiedUntil(),
        value.timeZone());
  }
}
