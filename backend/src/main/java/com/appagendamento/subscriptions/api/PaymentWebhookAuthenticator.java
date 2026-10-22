package com.appagendamento.subscriptions.api;

import java.util.Optional;

public interface PaymentWebhookAuthenticator {

  default boolean isConfigured() {
    return true;
  }

  String providerCode();

  Optional<AuthenticatedPaymentEvent> authenticate(byte[] rawBody, String signatureHeader);
}
