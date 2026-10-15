package com.appagendamento.catalog.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record ServiceVersionRequest(@NotNull @PositiveOrZero Long expectedVersion) {}
