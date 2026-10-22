package com.appagendamento.subscriptions.application;

public class SubscriptionCheckoutConflictException extends RuntimeException {

  public SubscriptionCheckoutConflictException(String code) {
    super(code);
  }
}
