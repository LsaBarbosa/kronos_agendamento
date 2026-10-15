package com.appagendamento.catalog.domain;

public final class ServiceNotFoundException extends RuntimeException {

  public ServiceNotFoundException() {
    super("Serviço não encontrado");
  }
}
