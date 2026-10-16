package com.appagendamento.availability.api;

import com.appagendamento.availability.domain.ScheduleExceptionKind;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

public record SaveScheduleExceptionRequest(
    @NotNull LocalDate localDate,
    @NotNull ScheduleExceptionKind kind,
    @NotNull List<@jakarta.validation.Valid SpecialPeriodRequest> periods,
    @Size(max = 500) String reason,
    @NotNull Long expectedVersion) {}
