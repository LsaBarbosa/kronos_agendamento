package com.appagendamento.tenant.domain;

public final class TenantVersionConflictException extends RuntimeException {

  public TenantVersionConflictException() {
    super("Versão do tenant divergente");
  }
}
