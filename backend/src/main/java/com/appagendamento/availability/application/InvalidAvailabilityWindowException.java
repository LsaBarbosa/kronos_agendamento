package com.appagendamento.availability.application;

public final class InvalidAvailabilityWindowException extends RuntimeException {

  public static final String CODE = "INVALID_AVAILABILITY_WINDOW";

  public InvalidAvailabilityWindowException() {
    super("A janela deve conter de 1 a 31 datas locais, com início não posterior ao fim");
  }

  public String code() {
    return CODE;
  }
}
