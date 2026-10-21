package com.appagendamento.subscriptions.api;

import com.appagendamento.subscriptions.domain.PlanCode;
import java.util.Optional;

public interface BillingOfferCatalog {

  Optional<BillingOffer> findApprovedOffer(PlanCode planCode);
}
