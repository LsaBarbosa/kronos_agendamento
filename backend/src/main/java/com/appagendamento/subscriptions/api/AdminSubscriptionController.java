package com.appagendamento.subscriptions.api;

import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.subscriptions.application.GetAdminCheckoutOperation;
import com.appagendamento.subscriptions.application.GetAdminSubscription;
import com.appagendamento.subscriptions.application.ListAdminCheckoutOperations;
import com.appagendamento.subscriptions.application.StartSubscriptionCheckout;
import com.appagendamento.subscriptions.domain.CheckoutView;
import com.appagendamento.subscriptions.domain.PlanCode;
import com.appagendamento.subscriptions.domain.Subscription;
import com.appagendamento.subscriptions.domain.SubscriptionPlan;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminSubscriptionController {

  private final GetAdminSubscription getSubscription;
  private final StartSubscriptionCheckout startCheckout;
  private final ListAdminCheckoutOperations listCheckouts;
  private final GetAdminCheckoutOperation getCheckout;

  public AdminSubscriptionController(
      GetAdminSubscription getSubscription,
      StartSubscriptionCheckout startCheckout,
      ListAdminCheckoutOperations listCheckouts,
      GetAdminCheckoutOperation getCheckout) {
    this.getSubscription = getSubscription;
    this.startCheckout = startCheckout;
    this.listCheckouts = listCheckouts;
    this.getCheckout = getCheckout;
  }

  @org.springframework.web.bind.annotation.PostMapping("/api/v1/admin/subscription/checkout")
  ResponseEntity<CheckoutView> checkout(
      @AuthenticationPrincipal AuthenticatedAdmin admin,
      @org.springframework.web.bind.annotation.RequestHeader("Idempotency-Key") String key,
      @org.springframework.web.bind.annotation.RequestHeader("X-Expected-Tenant-Context")
          UUID expectedTenantContextId,
      @org.springframework.web.bind.annotation.RequestBody CheckoutRequest request) {
    CheckoutView view =
        startCheckout.execute(
            admin, expectedTenantContextId, request.planCode(), request.expectedVersion(), key);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view);
  }

  @GetMapping("/api/v1/admin/subscription/payment-attempts")
  ResponseEntity<List<CheckoutAttemptView>> attempts(
      @AuthenticationPrincipal AuthenticatedAdmin admin,
      @org.springframework.web.bind.annotation.RequestHeader("X-Expected-Tenant-Context")
          UUID expectedTenantContextId) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            listCheckouts.execute(admin, expectedTenantContextId).stream()
                .map(CheckoutAttemptView::from)
                .toList());
  }

  @GetMapping("/api/v1/admin/subscription/payment-attempts/{operationId}")
  ResponseEntity<CheckoutView> attempt(
      @AuthenticationPrincipal AuthenticatedAdmin admin,
      @org.springframework.web.bind.annotation.RequestHeader("X-Expected-Tenant-Context")
          UUID expectedTenantContextId,
      @org.springframework.web.bind.annotation.PathVariable UUID operationId) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(getCheckout.execute(admin, expectedTenantContextId, operationId));
  }

  record CheckoutRequest(PlanCode planCode, long expectedVersion) {}

  record CheckoutAttemptView(
      UUID operationId,
      PlanCode planCode,
      String state,
      java.time.Instant createdAt,
      long expectedSubscriptionVersion) {
    static CheckoutAttemptView from(com.appagendamento.subscriptions.domain.CheckoutOperation op) {
      return new CheckoutAttemptView(
          op.operationId(),
          op.planCode(),
          op.state(),
          op.createdAt(),
          op.expectedSubscriptionVersion());
    }
  }

  @GetMapping("/api/v1/admin/subscription")
  ResponseEntity<AdminSubscriptionResponse> current(
      @AuthenticationPrincipal AuthenticatedAdmin admin) {
    var overview = getSubscription.execute(admin);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            new AdminSubscriptionResponse(
                overview.tenantContextId(),
                SubscriptionView.from(overview.subscription()),
                overview.plans(),
                overview.checkoutOffers(),
                !overview.checkoutOffers().isEmpty(),
                overview.subscription() == null,
                overview.eligibility(),
                overview.dunningAttempts()));
  }

  record AdminSubscriptionResponse(
      UUID tenantContextId,
      SubscriptionView subscription,
      List<SubscriptionPlan> plans,
      List<BillingOffer> checkoutOffers,
      boolean checkoutAvailable,
      boolean trialEligibilityPending,
      String commercialEligibility,
      List<com.appagendamento.subscriptions.domain.DunningAttemptView> dunningAttempts) {}

  record SubscriptionView(
      java.util.UUID subscriptionId,
      String status,
      java.time.Instant trialStartedAt,
      java.time.Instant trialEndsAt,
      String planCode,
      java.time.Instant paidThroughAt,
      java.time.Instant graceEndsAt,
      long version) {

    static SubscriptionView from(Subscription subscription) {
      if (subscription == null) return null;
      return new SubscriptionView(
          subscription.subscriptionId(),
          subscription.status(),
          subscription.trialStartedAt(),
          subscription.trialEndsAt(),
          subscription.planCode(),
          subscription.paidThroughAt(),
          subscription.graceEndsAt(),
          subscription.version());
    }
  }
}
