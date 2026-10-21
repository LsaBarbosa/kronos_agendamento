package com.appagendamento.subscriptions.api;

import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.subscriptions.application.GetAdminSubscription;
import com.appagendamento.subscriptions.domain.Subscription;
import com.appagendamento.subscriptions.domain.SubscriptionPlan;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminSubscriptionController {

  private final GetAdminSubscription getSubscription;

  public AdminSubscriptionController(GetAdminSubscription getSubscription) {
    this.getSubscription = getSubscription;
  }

  @GetMapping("/api/v1/admin/subscription")
  ResponseEntity<AdminSubscriptionResponse> current(
      @AuthenticationPrincipal AuthenticatedAdmin admin) {
    var overview = getSubscription.execute(admin);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            new AdminSubscriptionResponse(
                SubscriptionView.from(overview.subscription()),
                overview.plans(),
                overview.subscription() == null,
                overview.eligibility()));
  }

  record AdminSubscriptionResponse(
      SubscriptionView subscription,
      List<SubscriptionPlan> plans,
      boolean trialEligibilityPending,
      String commercialEligibility) {}

  record SubscriptionView(
      java.util.UUID subscriptionId,
      String status,
      java.time.Instant trialStartedAt,
      java.time.Instant trialEndsAt,
      long version) {

    static SubscriptionView from(Subscription subscription) {
      if (subscription == null) return null;
      return new SubscriptionView(
          subscription.subscriptionId(),
          subscription.status(),
          subscription.trialStartedAt(),
          subscription.trialEndsAt(),
          subscription.version());
    }
  }
}
