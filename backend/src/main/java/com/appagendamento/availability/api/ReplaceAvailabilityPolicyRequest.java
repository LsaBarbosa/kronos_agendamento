package com.appagendamento.availability.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record ReplaceAvailabilityPolicyRequest(
    @NotNull @PositiveOrZero Long expectedVersion,
    @NotNull Integer slotStepMinutes,
    @NotNull Integer minimumNoticeHours,
    @NotNull Integer bookingHorizonDays,
    @NotNull Integer cancellationCutoffHours,
    @NotNull Integer reminderOffsetHours) {}
