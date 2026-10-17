package com.appagendamento.appointments.domain;

public class PublicBookingRecoveryTemporarilyUnavailableException extends RuntimeException {

  public PublicBookingRecoveryTemporarilyUnavailableException(Throwable cause) {
    super("O estado de recuperação da reserva está temporariamente indisponível", cause);
  }
}
