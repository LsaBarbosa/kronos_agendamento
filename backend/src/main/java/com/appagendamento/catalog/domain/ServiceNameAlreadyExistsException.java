package com.appagendamento.catalog.domain;

public final class ServiceNameAlreadyExistsException extends RuntimeException {

  public ServiceNameAlreadyExistsException() {
    super("Nome indisponível para serviço ativo");
  }
}
