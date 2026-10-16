package com.appagendamento.availability.domain;

public final class InvalidLocalTimeException extends ScheduleValidationException {

  public InvalidLocalTimeException(String message) {
    super(message);
  }
}
