package com.appagendamento.appointments.api;

import com.appagendamento.appointments.application.AdministrativeRescheduleReceipt;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AdministrativeRescheduleResponse(
    UUID appointmentId,
    UUID operationId,
    long version,
    OffsetDateTime startsAt,
    OffsetDateTime serviceEndsAt,
    OffsetDateTime occupiedUntil,
    String timeZone) {

  public static AdministrativeRescheduleResponse from(AdministrativeRescheduleReceipt receipt) {
    return new AdministrativeRescheduleResponse(
        receipt.appointmentId(),
        receipt.operationId(),
        receipt.version(),
        receipt.startsAt(),
        receipt.serviceEndsAt(),
        receipt.occupiedUntil(),
        receipt.timeZone());
  }
}
