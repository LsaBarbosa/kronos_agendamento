package com.appagendamento.appointments.domain;

public class PublicAppointmentUnavailableException extends RuntimeException {

  public PublicAppointmentUnavailableException() {
    super("Negócio ou serviço não encontrado.");
  }
}
