package com.appagendamento.availability.api;

import com.appagendamento.availability.application.GetScheduleExceptions;
import java.time.LocalDate;
import java.util.List;

public record ScheduleExceptionListResponse(
    LocalDate from, LocalDate to, String timeZone, List<ScheduleExceptionResponse> exceptions) {
  public static ScheduleExceptionListResponse from(GetScheduleExceptions.Query value) {
    return new ScheduleExceptionListResponse(
        value.from(),
        value.to(),
        value.timeZone(),
        value.exceptions().stream().map(ScheduleExceptionResponse::from).toList());
  }
}
