package com.appagendamento.tenant.api;

import java.util.UUID;

public interface TenantTrialProvisioner {

  void provisionIfEligible(UUID tenantId);
}
