package com.appagendamento.availability.domain;

public class ScheduleValidationException extends IllegalArgumentException {

  public ScheduleValidationException(String message) {
    super(message);
  }
}
