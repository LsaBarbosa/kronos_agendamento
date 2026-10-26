package com.appagendamento.clients.api;

import java.util.UUID;

/** Read-only, minimized facts for the tenant retention assessment. */
public interface ClientRetentionFactsQuery {

  Facts query(UUID tenantId);

  record Count(int value, boolean atLeastLimit) {}

  record Facts(Count clients) {}
}
