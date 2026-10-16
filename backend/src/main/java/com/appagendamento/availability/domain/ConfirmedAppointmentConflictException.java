package com.appagendamento.availability.domain;

public class ConfirmedAppointmentConflictException extends RuntimeException {

  public ConfirmedAppointmentConflictException() {
    super("O intervalo conflita com uma reserva confirmada");
  }
}
