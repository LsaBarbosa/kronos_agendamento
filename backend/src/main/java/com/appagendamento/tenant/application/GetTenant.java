package com.appagendamento.tenant.application;

import com.appagendamento.identityaccess.api.AuthenticatedTenant;
import com.appagendamento.tenant.domain.Tenant;
import com.appagendamento.tenant.domain.TenantNotFoundException;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class GetTenant {

  private final TenantRepository tenants;

  public GetTenant(TenantRepository tenants) {
    this.tenants = tenants;
  }

  public Tenant execute(AuthenticatedTenant actor, UUID requestedTenantId) {
    return tenants
        .findByTenantIdAndId(actor.tenantId(), requestedTenantId)
        .orElseThrow(TenantNotFoundException::new);
  }
}
