package com.appagendamento.appointments.domain;

public class AppointmentDataUnavailableException extends RuntimeException {

  public AppointmentDataUnavailableException(Throwable cause) {
    super("A fonte de reservas está temporariamente indisponível", cause);
  }
}
