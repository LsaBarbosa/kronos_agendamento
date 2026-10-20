package com.appagendamento.clients.application;

public class ClientNoShowPolicyVersionConflictException extends RuntimeException {

  public ClientNoShowPolicyVersionConflictException() {
    super("A política de faltas mudou");
  }
}
