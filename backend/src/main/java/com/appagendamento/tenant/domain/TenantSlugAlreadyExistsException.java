package com.appagendamento.tenant.domain;

public final class TenantSlugAlreadyExistsException extends RuntimeException {

  public TenantSlugAlreadyExistsException() {
    super("Slug indisponível");
  }
}
