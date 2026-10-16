package com.appagendamento.availability.api;

import com.appagendamento.availability.domain.WeeklyPeriod;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.DayOfWeek;
import java.time.LocalTime;

public record WeeklyPeriodRequest(
    @NotNull DayOfWeek weekday,
    @NotNull @Pattern(regexp = "(?:[01][0-9]|2[0-3]):(?:00|15|30|45)") String localStart,
    @NotNull @Pattern(regexp = "(?:[01][0-9]|2[0-3]):(?:00|15|30|45)") String localEnd) {

  public WeeklyPeriod toDomain() {
    return new WeeklyPeriod(weekday, LocalTime.parse(localStart), LocalTime.parse(localEnd));
  }
}
