package com.appagendamento.catalog.api;

import com.appagendamento.catalog.domain.ServiceStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateServiceRequest(
    @NotBlank String name,
    String description,
    int durationMinutes,
    int bufferAfterMinutes,
    @NotNull ServiceStatus status) {}
