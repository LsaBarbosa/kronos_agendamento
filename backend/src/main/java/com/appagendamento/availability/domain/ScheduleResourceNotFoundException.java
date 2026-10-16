package com.appagendamento.availability.domain;

public class ScheduleResourceNotFoundException extends RuntimeException {

  public ScheduleResourceNotFoundException() {
    super("Recurso de agenda não encontrado");
  }
}
