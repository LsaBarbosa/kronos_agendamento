package com.appagendamento.subscriptions.application;

import java.time.Instant;
import java.util.UUID;

/** Local, server-authoritative lookup of a current monthly recurring-charge mandate. */
public interface DunningChargeAuthorization {

  /**
   * Implementations must read a locally available trusted monthly authorization and must not
   * perform network I/O. Annual renewal uses {@link AnnualRenewalAuthorization} instead. The lookup
   * is made while the tenant billing/booking lock is held.
   */
  boolean isAuthorized(UUID tenantId, UUID subscriptionId, String planCode, Instant asOf);
}
