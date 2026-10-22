package com.appagendamento.subscriptions.infrastructure;

import com.appagendamento.subscriptions.api.PaymentWebhookAuthenticator;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!e2e")
public class DisabledPaymentWebhookAuthenticator implements PaymentWebhookAuthenticator {

  @Override
  public boolean isConfigured() {
    return false;
  }

  @Override
  public String providerCode() {
    return "unconfigured";
  }

  @Override
  public Optional<com.appagendamento.subscriptions.api.AuthenticatedPaymentEvent> authenticate(
      byte[] rawBody, String signatureHeader) {
    return Optional.empty();
  }
}
