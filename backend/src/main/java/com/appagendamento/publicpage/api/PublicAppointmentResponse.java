package com.appagendamento.publicpage.api;

import com.appagendamento.appointments.application.PublicAppointmentReceipt;
import java.time.OffsetDateTime;
import java.util.UUID;

public record PublicAppointmentResponse(
    UUID appointmentId,
    UUID operationId,
    String status,
    String serviceName,
    OffsetDateTime startsAt,
    OffsetDateTime serviceEndsAt,
    String timeZone) {

  public static PublicAppointmentResponse from(PublicAppointmentReceipt receipt) {
    return new PublicAppointmentResponse(
        receipt.appointmentId(),
        receipt.operationId(),
        receipt.status(),
        receipt.serviceName(),
        receipt.startsAt(),
        receipt.serviceEndsAt(),
        receipt.timeZone());
  }
}
