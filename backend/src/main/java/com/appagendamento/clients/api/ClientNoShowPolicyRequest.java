package com.appagendamento.clients.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ClientNoShowPolicyRequest(
    @NotNull @Min(0) Long expectedVersion, @NotNull @Min(1) @Max(5) Integer threshold) {}
