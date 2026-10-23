package com.appagendamento.subscriptions.application;

import java.time.Instant;
import java.util.UUID;

/** Local lookup of an explicit authorization for one annual renewal period. */
public interface AnnualRenewalAuthorization {

  /** Must not perform network I/O and is called while the tenant lifecycle lock is held. */
  boolean isAuthorizedForPeriod(
      UUID tenantId, UUID subscriptionId, Instant periodEndsAt, Instant asOf);
}
