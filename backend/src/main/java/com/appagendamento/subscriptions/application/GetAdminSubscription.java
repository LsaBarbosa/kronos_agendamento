package com.appagendamento.subscriptions.application;

import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.subscriptions.api.BillingOffer;
import com.appagendamento.subscriptions.api.BillingOfferCatalog;
import com.appagendamento.subscriptions.api.HostedCheckoutProvider;
import com.appagendamento.subscriptions.api.PublicSubscriptionEligibility;
import com.appagendamento.subscriptions.domain.PlanCatalog;
import com.appagendamento.subscriptions.domain.Subscription;
import com.appagendamento.subscriptions.domain.SubscriptionPlan;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class GetAdminSubscription {

  private final AdminAccessPolicy access;
  private final SubscriptionRepository subscriptions;
  private final PublicSubscriptionEligibility lifecycle;
  private final BillingOfferCatalog billingOffers;
  private final HostedCheckoutProvider checkoutProvider;

  public GetAdminSubscription(
      AdminAccessPolicy access,
      SubscriptionRepository subscriptions,
      PublicSubscriptionEligibility lifecycle,
      BillingOfferCatalog billingOffers,
      HostedCheckoutProvider checkoutProvider) {
    this.access = access;
    this.subscriptions = subscriptions;
    this.lifecycle = lifecycle;
    this.billingOffers = billingOffers;
    this.checkoutProvider = checkoutProvider;
  }

  public Overview execute(AuthenticatedAdmin admin) {
    if (admin == null || !"TENANT_ADMIN".equals(admin.role()) || !access.isCurrent(admin)) {
      throw new SubscriptionAccessDeniedException();
    }
    lifecycle.allowsNewPublicAppointments(admin.tenantId());
    Optional<Subscription> subscription = subscriptions.findCurrent(admin.tenantId());
    return new Overview(
        subscription.orElse(null),
        PlanCatalog.plans(),
        checkoutProvider.isConfigured()
            ? PlanCatalog.plans().stream()
                .map(plan -> billingOffers.findApprovedOffer(plan.code()).orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList()
            : List.of(),
        subscription.isEmpty() ? "VERIFICATION_UNAVAILABLE" : "VERIFIED");
  }

  public record Overview(
      Subscription subscription,
      List<SubscriptionPlan> plans,
      List<BillingOffer> checkoutOffers,
      String eligibility) {}
}
