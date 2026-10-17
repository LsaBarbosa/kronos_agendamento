package com.appagendamento.appointments.application;

import java.util.UUID;

public record AdministrativeAppointmentOutcomeCommand(
    UUID appointmentId, long expectedVersion, String reason) {
  public AdministrativeAppointmentOutcomeCommand {
    if (appointmentId == null || expectedVersion < 1) {
      throw new IllegalArgumentException("Comando de encerramento inválido");
    }
  }
}
