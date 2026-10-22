package com.appagendamento.subscriptions.application;

public class PaymentWebhookTooLargeException extends RuntimeException {

  public PaymentWebhookTooLargeException() {
    super("PAYMENT_EVENT_TOO_LARGE");
  }
}
