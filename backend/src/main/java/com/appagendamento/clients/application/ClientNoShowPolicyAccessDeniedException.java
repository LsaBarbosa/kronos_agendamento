package com.appagendamento.clients.application;

public class ClientNoShowPolicyAccessDeniedException extends RuntimeException {

  public ClientNoShowPolicyAccessDeniedException() {
    super("Acesso à política de faltas negado");
  }
}
