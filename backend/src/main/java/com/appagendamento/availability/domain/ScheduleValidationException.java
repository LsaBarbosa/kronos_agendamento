package com.appagendamento.availability.domain;

public final class ScheduleValidationException extends IllegalArgumentException {

  public ScheduleValidationException(String message) {
    super(message);
  }
}
