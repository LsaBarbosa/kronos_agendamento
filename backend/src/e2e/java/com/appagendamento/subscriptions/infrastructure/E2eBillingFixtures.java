package com.appagendamento.subscriptions.infrastructure;

import com.appagendamento.subscriptions.api.BillingOffer;
import com.appagendamento.subscriptions.api.BillingOfferCatalog;
import com.appagendamento.subscriptions.api.HostedCheckoutProvider;
import com.appagendamento.subscriptions.domain.PlanCode;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("e2e")
public class E2eBillingFixtures implements BillingOfferCatalog, HostedCheckoutProvider {

  private final ConcurrentMap<UUID, CheckoutSession> sessions = new ConcurrentHashMap<>();

  @Override
  public Optional<BillingOffer> findApprovedOffer(PlanCode planCode) {
    return Optional.of(new BillingOffer(planCode, 1, "XTS", "e2e-only", "e2e-v1"));
  }

  @Override
  public String providerCode() {
    return "e2e";
  }

  @Override
  public String providerAccountId() {
    return "synthetic-e2e-account";
  }

  @Override
  public CheckoutSession create(CheckoutCommand command) {
    return sessions.computeIfAbsent(
        command.operationId(),
        operationId ->
            new CheckoutSession(
                "e2e-" + operationId, "https://checkout.invalid/session/" + operationId));
  }

  @Override
  public Optional<CheckoutSession> findByOperationId(UUID operationId) {
    return Optional.ofNullable(sessions.get(operationId));
  }
}
