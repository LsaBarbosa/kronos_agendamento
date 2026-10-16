package com.appagendamento.availability.api;

import com.appagendamento.availability.domain.SpecialPeriod;
import java.time.LocalTime;

public record SpecialPeriodRequest(LocalTime localStart, LocalTime localEnd) {
  public SpecialPeriod toDomain() {
    return new SpecialPeriod(localStart, localEnd);
  }
}
