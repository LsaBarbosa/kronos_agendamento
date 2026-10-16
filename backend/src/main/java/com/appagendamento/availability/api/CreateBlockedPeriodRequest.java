package com.appagendamento.availability.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateBlockedPeriodRequest(
    @NotBlank String startsAt, @NotBlank String endsAt, @NotBlank @Size(max = 500) String reason) {}
