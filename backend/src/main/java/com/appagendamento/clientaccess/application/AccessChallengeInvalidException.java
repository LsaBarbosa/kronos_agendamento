package com.appagendamento.clientaccess.application;

public class AccessChallengeInvalidException extends RuntimeException {

  public AccessChallengeInvalidException() {
    super("Código inválido ou expirado.");
  }
}
