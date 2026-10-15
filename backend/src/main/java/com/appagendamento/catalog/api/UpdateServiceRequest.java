package com.appagendamento.catalog.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record UpdateServiceRequest(
    String name,
    String description,
    Integer durationMinutes,
    Integer bufferAfterMinutes,
    @NotNull @PositiveOrZero Long expectedVersion) {}
