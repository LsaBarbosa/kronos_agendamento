package com.appagendamento.publicpage.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;

public record PublicAppointmentRequest(
    @NotNull UUID serviceId,
    @NotNull @JsonFormat(without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
        OffsetDateTime startsAt,
    @NotNull @Valid Client client) {

  public record Client(
      @NotBlank @Size(max = 120) String name, @NotBlank @Size(max = 32) String phone) {}
}
