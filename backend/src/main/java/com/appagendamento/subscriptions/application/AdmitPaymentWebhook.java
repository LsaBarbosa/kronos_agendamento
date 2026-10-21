package com.appagendamento.subscriptions.application;

import com.appagendamento.subscriptions.api.AuthenticatedPaymentEvent;
import com.appagendamento.subscriptions.api.PaymentIntegrationUnavailableException;
import com.appagendamento.subscriptions.api.PaymentWebhookAuthenticator;
import com.appagendamento.subscriptions.domain.PaymentWebhookResult;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AdmitPaymentWebhook {

  private final List<PaymentWebhookAuthenticator> authenticators;
  private final BillingRepository billing;

  public AdmitPaymentWebhook(
      List<PaymentWebhookAuthenticator> authenticators, BillingRepository billing) {
    this.authenticators = authenticators;
    this.billing = billing;
  }

  public PaymentWebhookResult execute(String providerCode, byte[] rawBody, String signature) {
    if (rawBody == null || rawBody.length == 0) {
      throw new InvalidPaymentWebhookException();
    }
    if (rawBody.length > 65_536) throw new PaymentWebhookTooLargeException();
    PaymentWebhookAuthenticator authenticator =
        authenticators.stream()
            .filter(PaymentWebhookAuthenticator::isConfigured)
            .filter(value -> value.providerCode().equals(providerCode))
            .findFirst()
            .orElseThrow(PaymentIntegrationUnavailableException::new);
    AuthenticatedPaymentEvent event =
        authenticator
            .authenticate(rawBody.clone(), signature)
            .filter(value -> providerCode.equals(value.providerCode()))
            .orElseThrow(InvalidPaymentWebhookException::new);
    boolean inserted = billing.recordAuthenticatedEvent(event);
    return inserted ? PaymentWebhookResult.admitted() : PaymentWebhookResult.duplicate();
  }
}
