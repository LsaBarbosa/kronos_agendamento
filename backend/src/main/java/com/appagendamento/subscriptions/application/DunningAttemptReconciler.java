package com.appagendamento.subscriptions.application;

import java.util.UUID;

/** Optional authenticated provider lookup; absence leaves OUTCOME_UNKNOWN blocked. */
public interface DunningAttemptReconciler {

  DunningChargeTransport.TransportResult reconcile(
      UUID tenantId, UUID attemptId, UUID externalIdempotencyKey, String providerReference);
}
