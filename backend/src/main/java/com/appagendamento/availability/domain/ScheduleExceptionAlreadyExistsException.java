package com.appagendamento.availability.domain;

public class ScheduleExceptionAlreadyExistsException extends RuntimeException {

  public ScheduleExceptionAlreadyExistsException() {
    super("Já existe uma exceção para esta data");
  }
}
