package com.appagendamento.availability.api;

import jakarta.validation.constraints.NotNull;

public record RemoveScheduleExceptionRequest(@NotNull Long expectedVersion) {}
