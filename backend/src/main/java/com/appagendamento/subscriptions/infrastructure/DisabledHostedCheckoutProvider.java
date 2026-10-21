package com.appagendamento.subscriptions.infrastructure;

import com.appagendamento.subscriptions.api.HostedCheckoutProvider;
import com.appagendamento.subscriptions.api.PaymentIntegrationUnavailableException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!e2e")
public class DisabledHostedCheckoutProvider implements HostedCheckoutProvider {

  @Override
  public boolean isConfigured() {
    return false;
  }

  @Override
  public String providerCode() {
    return "unconfigured";
  }

  @Override
  public String providerAccountId() {
    return "unconfigured";
  }

  @Override
  public CheckoutSession create(CheckoutCommand command) {
    throw new PaymentIntegrationUnavailableException();
  }

  @Override
  public Optional<CheckoutSession> findByOperationId(UUID operationId) {
    throw new PaymentIntegrationUnavailableException();
  }
}
