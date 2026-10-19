package com.appagendamento.notifications.domain;

public enum TransportFailure {
  RETRYABLE_NO_EFFECT("TRANSPORT_RETRYABLE"),
  DEFINITIVE("TRANSPORT_TERMINAL"),
  AMBIGUOUS("TRANSPORT_OUTCOME_UNKNOWN");

  private final String safeCode;

  TransportFailure(String safeCode) {
    this.safeCode = safeCode;
  }

  public String safeCode() {
    return safeCode;
  }
}
