package com.appagendamento.appointments.application;

import java.util.UUID;

public record ClientAppointmentCancellationCommand(UUID appointmentId, long expectedVersion) {

  public ClientAppointmentCancellationCommand {
    if (appointmentId == null || expectedVersion < 1) {
      throw new IllegalArgumentException("Comando de cancelamento inválido");
    }
  }
}
