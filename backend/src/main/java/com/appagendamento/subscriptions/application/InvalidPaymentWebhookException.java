package com.appagendamento.subscriptions.application;

public class InvalidPaymentWebhookException extends RuntimeException {

  public InvalidPaymentWebhookException() {
    super("Evidência financeira inválida");
  }
}
