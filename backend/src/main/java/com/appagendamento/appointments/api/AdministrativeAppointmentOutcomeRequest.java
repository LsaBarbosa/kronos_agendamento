package com.appagendamento.appointments.api;

import com.appagendamento.appointments.application.AdministrativeAppointmentOutcomeCommand;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record AdministrativeAppointmentOutcomeRequest(
    @NotNull @Positive Long expectedVersion, @Size(max = 500) String reason) {

  public AdministrativeAppointmentOutcomeCommand toCommand(UUID appointmentId) {
    return new AdministrativeAppointmentOutcomeCommand(appointmentId, expectedVersion, reason);
  }
}
