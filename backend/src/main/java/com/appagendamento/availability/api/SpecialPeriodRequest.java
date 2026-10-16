package com.appagendamento.availability.api;

import com.appagendamento.availability.domain.SpecialPeriod;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalTime;
import java.time.ZoneOffset;

public record SpecialPeriodRequest(
    @NotNull @Pattern(regexp = "(?:[01][0-9]|2[0-3]):(?:00|15|30|45)") String localStart,
    @NotNull @Pattern(regexp = "(?:[01][0-9]|2[0-3]):(?:00|15|30|45)") String localEnd,
    @Pattern(regexp = "[+-](?:(?:0[0-9]|1[0-7]):[0-5][0-9]|18:00)") String localStartOffset,
    @Pattern(regexp = "[+-](?:(?:0[0-9]|1[0-7]):[0-5][0-9]|18:00)") String localEndOffset) {

  public SpecialPeriod toDomain() {
    return new SpecialPeriod(
        LocalTime.parse(localStart),
        LocalTime.parse(localEnd),
        parseOffset(localStartOffset),
        parseOffset(localEndOffset));
  }

  private static ZoneOffset parseOffset(String value) {
    return value == null ? null : ZoneOffset.of(value);
  }
}
