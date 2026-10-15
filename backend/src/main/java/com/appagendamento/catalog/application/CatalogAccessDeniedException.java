package com.appagendamento.catalog.application;

public final class CatalogAccessDeniedException extends RuntimeException {

  public CatalogAccessDeniedException() {
    super("Acesso ao catálogo não autorizado");
  }
}
