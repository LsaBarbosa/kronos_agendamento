package com.appagendamento.outbox.api;

import java.util.UUID;

/** Read-only, minimized outbox lifecycle counts for retention assessment. */
public interface OutboxRetentionFactsQuery {

  Facts query(UUID tenantId);

  record Count(int value, boolean atLeastLimit) {}

  record Facts(Count events, Count pendingOrClaimed, Count reconciliationRequired) {}
}
