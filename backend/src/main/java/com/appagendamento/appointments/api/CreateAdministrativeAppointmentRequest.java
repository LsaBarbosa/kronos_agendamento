package com.appagendamento.appointments.api;

import com.appagendamento.appointments.application.CreateAdministrativeAppointmentCommand;
import com.appagendamento.appointments.domain.AdministrativeOverridePolicy;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record CreateAdministrativeAppointmentRequest(
    @NotNull UUID serviceId,
    @NotNull @JsonFormat(without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
        OffsetDateTime startsAt,
    UUID clientId,
    @Valid AdministrativeClientRequest client,
    @Size(max = 2) List<AdministrativeOverridePolicy> overridePolicies,
    @Size(max = 500) String reason,
    @Min(1) long expectedScheduleVersion) {

  public CreateAdministrativeAppointmentCommand toCommand() {
    return new CreateAdministrativeAppointmentCommand(
        serviceId,
        startsAt,
        clientId,
        client == null ? null : client.name(),
        client == null ? null : client.phone(),
        overridePolicies,
        reason,
        expectedScheduleVersion);
  }
}
