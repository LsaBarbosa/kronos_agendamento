package com.appagendamento.appointments.api;

import com.appagendamento.appointments.application.RescheduleAdministrativeAppointmentCommand;
import com.appagendamento.appointments.domain.AdministrativeOverridePolicy;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record RescheduleAdministrativeAppointmentRequest(
    @NotNull @Positive Long expectedVersion,
    @NotNull @PositiveOrZero Long expectedScheduleRevision,
    @NotNull @JsonFormat(without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
        OffsetDateTime startsAt,
    @Size(max = 2) List<@NotNull AdministrativeOverridePolicy> overridePolicies,
    @Size(max = 500) String reason) {

  public RescheduleAdministrativeAppointmentCommand toCommand(UUID appointmentId) {
    return new RescheduleAdministrativeAppointmentCommand(
        appointmentId,
        expectedVersion,
        expectedScheduleRevision,
        startsAt,
        overridePolicies,
        reason);
  }
}
