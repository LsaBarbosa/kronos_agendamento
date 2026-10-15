package com.appagendamento.catalog.domain;

public final class ServiceVersionConflictException extends RuntimeException {

  public ServiceVersionConflictException() {
    super("Versão do serviço divergente");
  }
}
