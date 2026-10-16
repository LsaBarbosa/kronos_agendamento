package com.appagendamento.clients.domain;

public final class InvalidClientNameException extends IllegalArgumentException {

  public InvalidClientNameException() {
    super("Nome do cliente inválido");
  }
}
