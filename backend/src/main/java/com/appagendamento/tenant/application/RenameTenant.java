package com.appagendamento.tenant.application;

import com.appagendamento.identityaccess.api.AuthenticatedTenant;
import com.appagendamento.tenant.domain.Tenant;
import com.appagendamento.tenant.domain.TenantNotFoundException;
import com.appagendamento.tenant.domain.TenantVersionConflictException;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RenameTenant {

  private final TenantRepository tenants;

  public RenameTenant(TenantRepository tenants) {
    this.tenants = tenants;
  }

  public Tenant execute(
      AuthenticatedTenant actor, UUID requestedTenantId, String newName, long expectedVersion) {
    Tenant current =
        tenants
            .findByTenantIdAndId(actor.tenantId(), requestedTenantId)
            .orElseThrow(TenantNotFoundException::new);
    if (current.version() != expectedVersion) {
      throw new TenantVersionConflictException();
    }
    Tenant renamed = current.rename(newName);
    if (!tenants.rename(actor.tenantId(), requestedTenantId, renamed.name(), expectedVersion)) {
      throw new TenantVersionConflictException();
    }
    return tenants
        .findByTenantIdAndId(actor.tenantId(), requestedTenantId)
        .orElseThrow(TenantNotFoundException::new);
  }
}
