package com.appagendamento.tenant.api;

import java.util.UUID;

/** Exposes only the persisted lifecycle status needed by the retention assessment. */
public interface TenantRetentionFactsQuery {

  Facts query(UUID tenantId);

  record Facts(String lifecycleStatus) {}
}
