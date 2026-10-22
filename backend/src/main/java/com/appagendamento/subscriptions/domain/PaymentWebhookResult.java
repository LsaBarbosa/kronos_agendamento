package com.appagendamento.subscriptions.domain;

public record PaymentWebhookResult(String result) {

  public static PaymentWebhookResult admitted() {
    return new PaymentWebhookResult("ADMITTED");
  }

  public static PaymentWebhookResult duplicate() {
    return new PaymentWebhookResult("DUPLICATE");
  }
}
