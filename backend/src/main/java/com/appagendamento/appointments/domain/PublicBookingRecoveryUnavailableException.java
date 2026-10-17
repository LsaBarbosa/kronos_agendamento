package com.appagendamento.appointments.domain;

public class PublicBookingRecoveryUnavailableException extends RuntimeException {

  public PublicBookingRecoveryUnavailableException() {
    super("A recuperação desta intenção não está disponível");
  }
}
