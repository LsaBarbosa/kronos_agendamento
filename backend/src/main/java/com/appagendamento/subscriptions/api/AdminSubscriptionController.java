package com.appagendamento.subscriptions.api;

import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.subscriptions.domain.PlanCatalog;
import com.appagendamento.subscriptions.domain.SubscriptionPlan;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminSubscriptionController {

  @GetMapping("/api/v1/admin/subscription")
  ResponseEntity<AdminSubscriptionResponse> current(
      @AuthenticationPrincipal AuthenticatedAdmin admin) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(new AdminSubscriptionResponse(null, PlanCatalog.plans(), true));
  }

  record AdminSubscriptionResponse(
      Object subscription, List<SubscriptionPlan> plans, boolean trialEligibilityPending) {}
}
