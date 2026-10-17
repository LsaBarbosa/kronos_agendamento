package com.appagendamento.appointments.domain;

public class PublicBookingDigestKeyUnavailableException extends RuntimeException {

  public PublicBookingDigestKeyUnavailableException(String keyId) {
    super("Chave histórica de digest público indisponível: " + keyId);
  }
}
