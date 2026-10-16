package com.appagendamento.availability.api;

import com.appagendamento.availability.domain.ScheduleException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

public record ScheduleExceptionResponse(
    UUID exceptionId,
    LocalDate localDate,
    String kind,
    List<SpecialPeriodResponse> periods,
    String reason,
    long version,
    String timeZone) {
  public static ScheduleExceptionResponse from(ScheduleException value) {
    return new ScheduleExceptionResponse(
        value.exceptionId(),
        value.localDate(),
        value.kind().name(),
        value.periods().stream()
            .map(
                p ->
                    new SpecialPeriodResponse(
                        p.localStart().format(DateTimeFormatter.ofPattern("HH:mm")),
                        p.localEnd().format(DateTimeFormatter.ofPattern("HH:mm"))))
            .toList(),
        value.reason(),
        value.version(),
        value.timeZone());
  }
}
