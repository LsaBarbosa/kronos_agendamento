package com.appagendamento.appointments.application;

import com.appagendamento.appointments.domain.AdministrativeOverridePolicy;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record RescheduleAdministrativeAppointmentCommand(
    UUID appointmentId,
    long expectedVersion,
    long expectedScheduleRevision,
    OffsetDateTime startsAt,
    List<AdministrativeOverridePolicy> overridePolicies,
    String reason) {

  public RescheduleAdministrativeAppointmentCommand {
    if (appointmentId == null
        || expectedVersion < 1
        || expectedScheduleRevision < 0
        || startsAt == null) {
      throw new IllegalArgumentException("Comando de remarcação inválido");
    }
    overridePolicies = overridePolicies == null ? List.of() : List.copyOf(overridePolicies);
    if (overridePolicies.stream().distinct().count() != overridePolicies.size()) {
      throw new IllegalArgumentException("Políticas de override duplicadas");
    }
  }
}
