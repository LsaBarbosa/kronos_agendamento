package com.appagendamento.tenant.domain;

public final class TenantNotFoundException extends RuntimeException {

  public TenantNotFoundException() {
    super("Tenant não encontrado");
  }
}
