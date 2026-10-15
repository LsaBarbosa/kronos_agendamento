package com.appagendamento.catalog.application;

import com.appagendamento.catalog.domain.Service;
import com.appagendamento.catalog.domain.ServiceNotFoundException;
import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;

@org.springframework.stereotype.Service
public class GetService {

  private final ServiceRepository services;
  private final AdminAccessPolicy accessPolicy;

  public GetService(ServiceRepository services, AdminAccessPolicy accessPolicy) {
    this.services = services;
    this.accessPolicy = accessPolicy;
  }

  public Service execute(AuthenticatedAdmin actor, java.util.UUID serviceId) {
    if (!accessPolicy.isCurrent(actor)) {
      throw new CatalogAccessDeniedException();
    }
    return services
        .findByTenantAndId(actor.tenantId(), serviceId)
        .orElseThrow(ServiceNotFoundException::new);
  }
}
