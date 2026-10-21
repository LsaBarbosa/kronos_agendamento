package com.appagendamento.subscriptions.api;

public record VerifiedCommercialIdentity(String opaqueStableValue) {

  public VerifiedCommercialIdentity {
    if (opaqueStableValue == null || !opaqueStableValue.matches("[A-Za-z0-9_-]{32,256}")) {
      throw new IllegalArgumentException("Identidade comercial verificada inválida");
    }
  }
}
