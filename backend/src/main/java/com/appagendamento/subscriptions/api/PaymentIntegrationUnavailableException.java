package com.appagendamento.subscriptions.api;

public class PaymentIntegrationUnavailableException extends RuntimeException {

  public PaymentIntegrationUnavailableException() {
    super("Integração financeira indisponível");
  }
}
