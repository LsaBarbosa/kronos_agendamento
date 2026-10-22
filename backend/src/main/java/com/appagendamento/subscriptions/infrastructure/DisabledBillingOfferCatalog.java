package com.appagendamento.subscriptions.infrastructure;

import com.appagendamento.subscriptions.api.BillingOfferCatalog;
import com.appagendamento.subscriptions.domain.PlanCode;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!e2e")
public class DisabledBillingOfferCatalog implements BillingOfferCatalog {

  @Override
  public Optional<com.appagendamento.subscriptions.api.BillingOffer> findApprovedOffer(
      PlanCode planCode) {
    return Optional.empty();
  }
}
