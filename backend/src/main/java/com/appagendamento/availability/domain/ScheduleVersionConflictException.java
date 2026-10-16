package com.appagendamento.availability.domain;

public final class ScheduleVersionConflictException extends RuntimeException {

  public ScheduleVersionConflictException() {
    super("A configuração da agenda foi alterada por outra sessão");
  }
}
