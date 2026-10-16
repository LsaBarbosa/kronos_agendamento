package com.appagendamento.availability.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CancelBlockedPeriodRequest(
    @NotNull Long expectedVersion, @NotBlank @Size(max = 500) String reason) {}
