package com.appagendamento.subscriptions.api;

import java.util.UUID;

/** Read-only, minimized subscription and billing lifecycle counts. */
public interface BillingRetentionFactsQuery {

  Facts query(UUID tenantId);

  record Count(int value, boolean atLeastLimit) {}

  record Facts(
      String subscriptionStatus,
      Count subscriptions,
      Count openCheckouts,
      Count checkoutOutcomeUnknown,
      Count dunningPending,
      Count dunningOutcomeUnknown) {}
}
