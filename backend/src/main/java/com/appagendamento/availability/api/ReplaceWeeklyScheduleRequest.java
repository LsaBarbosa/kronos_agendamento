package com.appagendamento.availability.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;

public record ReplaceWeeklyScheduleRequest(
    @NotNull @PositiveOrZero Long expectedVersion,
    @NotNull List<@Valid WeeklyPeriodRequest> periods) {}
