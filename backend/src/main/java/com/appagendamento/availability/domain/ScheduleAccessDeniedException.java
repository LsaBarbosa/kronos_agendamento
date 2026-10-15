package com.appagendamento.availability.domain;

public final class ScheduleAccessDeniedException extends RuntimeException {

  public ScheduleAccessDeniedException() {
    super("Acesso à configuração da agenda não permitido");
  }
}
