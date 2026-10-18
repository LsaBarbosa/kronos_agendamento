package com.appagendamento.clientaccess.application;

public class InvalidAccessPhoneException extends RuntimeException {

  public InvalidAccessPhoneException() {
    super("Informe o telefone em formato internacional.");
  }
}
