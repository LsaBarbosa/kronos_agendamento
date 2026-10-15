package com.appagendamento.tenant.application;

import com.appagendamento.tenant.domain.Tenant;
import java.util.Optional;
import java.util.UUID;

public interface TenantRepository {

  Tenant insert(Tenant tenant);

  Optional<Tenant> findByTenantIdAndId(UUID authenticatedTenantId, UUID requestedTenantId);

  boolean rename(
      UUID authenticatedTenantId, UUID requestedTenantId, String name, long expectedVersion);
}
