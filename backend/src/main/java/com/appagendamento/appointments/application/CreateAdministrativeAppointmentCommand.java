package com.appagendamento.appointments.application;

import com.appagendamento.appointments.domain.AdministrativeOverridePolicy;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record CreateAdministrativeAppointmentCommand(
    UUID serviceId,
    OffsetDateTime startsAt,
    UUID clientId,
    String clientName,
    String clientPhone,
    List<AdministrativeOverridePolicy> overridePolicies,
    String reason,
    long expectedScheduleVersion) {

  public CreateAdministrativeAppointmentCommand {
    if (serviceId == null
        || startsAt == null
        || expectedScheduleVersion < 1
        || clientId != null && (clientName != null || clientPhone != null)
        || clientId == null && (clientName == null || clientPhone == null)) {
      throw new IllegalArgumentException("Comando de reserva administrativa inválido");
    }
    overridePolicies = overridePolicies == null ? List.of() : List.copyOf(overridePolicies);
    if (overridePolicies.stream().distinct().count() != overridePolicies.size()) {
      throw new IllegalArgumentException("Políticas de exceção duplicadas");
    }
  }
}
