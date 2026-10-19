package com.appagendamento.appointments.api;

import com.appagendamento.appointments.application.ClientAppointmentCancellationReceipt;
import java.util.UUID;

public record ClientAppointmentCancellationResponse(
    UUID appointmentId, String status, long version) {

  static ClientAppointmentCancellationResponse from(ClientAppointmentCancellationReceipt receipt) {
    return new ClientAppointmentCancellationResponse(
        receipt.appointmentId(), receipt.status(), receipt.version());
  }
}
