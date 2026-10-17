package com.appagendamento.appointments.api;

import com.appagendamento.appointments.application.AdministrativeAppointmentOutcomeReceipt;
import com.appagendamento.appointments.domain.AppointmentStatus;
import java.util.UUID;

public record AdministrativeAppointmentOutcomeResponse(
    UUID appointmentId, UUID operationId, AppointmentStatus status, long version) {

  public static AdministrativeAppointmentOutcomeResponse from(
      AdministrativeAppointmentOutcomeReceipt receipt) {
    return new AdministrativeAppointmentOutcomeResponse(
        receipt.appointmentId(), receipt.operationId(), receipt.status(), receipt.version());
  }
}
