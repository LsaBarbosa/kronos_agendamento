package com.appagendamento.tenant.application;

import com.appagendamento.tenant.domain.Tenant;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class CreateTenant {

  private final TenantRepository tenants;

  public CreateTenant(TenantRepository tenants) {
    this.tenants = tenants;
  }

  public Tenant execute(String name, String slug, String timezone) {
    return tenants.insert(Tenant.create(UUID.randomUUID(), name, slug, timezone));
  }
}
