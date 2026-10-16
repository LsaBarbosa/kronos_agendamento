package com.appagendamento.publicpage.domain;

public record PublicationBlocker(String code, String message) {

  public PublicationBlocker {
    if (code == null || code.isBlank() || message == null || message.isBlank()) {
      throw new IllegalArgumentException("Bloqueio de publicação inválido");
    }
  }
}
