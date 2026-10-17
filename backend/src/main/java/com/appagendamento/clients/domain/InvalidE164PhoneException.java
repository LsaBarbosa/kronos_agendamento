package com.appagendamento.clients.domain;

public final class InvalidE164PhoneException extends IllegalArgumentException {

  public InvalidE164PhoneException() {
    super("Telefone E.164 inválido");
  }
}
