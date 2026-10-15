package com.appagendamento.catalog.application;

import com.appagendamento.catalog.domain.Service;
import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import java.util.List;

@org.springframework.stereotype.Service
public class ListServices {

  private final ServiceRepository services;
  private final AdminAccessPolicy accessPolicy;

  public ListServices(ServiceRepository services, AdminAccessPolicy accessPolicy) {
    this.services = services;
    this.accessPolicy = accessPolicy;
  }

  public List<Service> execute(AuthenticatedAdmin actor) {
    if (!accessPolicy.isCurrent(actor)) {
      throw new CatalogAccessDeniedException();
    }
    return services.findAllByTenant(actor.tenantId());
  }
}
