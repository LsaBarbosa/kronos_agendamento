package com.appagendamento.appointments.domain;

public class InvalidAppointmentTimeException extends RuntimeException {

  public InvalidAppointmentTimeException() {
    super("O horário informado não corresponde ao fuso do negócio");
  }
}
