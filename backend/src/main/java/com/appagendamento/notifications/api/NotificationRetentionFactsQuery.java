package com.appagendamento.notifications.api;

import java.util.UUID;

/** Read-only, minimized notification lifecycle counts for retention assessment. */
public interface NotificationRetentionFactsQuery {

  Facts query(UUID tenantId);

  record Count(int value, boolean atLeastLimit) {}

  record Facts(Count intents, Count pendingOrClaimed, Count outcomeUnknown, Count accepted) {}
}
