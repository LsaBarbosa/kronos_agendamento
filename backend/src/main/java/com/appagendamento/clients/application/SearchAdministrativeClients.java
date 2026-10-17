package com.appagendamento.clients.application;

import com.appagendamento.clients.api.AdministrativeClientRegistry;
import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class SearchAdministrativeClients {

  private final AdministrativeClientRegistry clients;
  private final AdminAccessPolicy accessPolicy;

  public SearchAdministrativeClients(
      AdministrativeClientRegistry clients, AdminAccessPolicy accessPolicy) {
    this.clients = clients;
    this.accessPolicy = accessPolicy;
  }

  public List<AdministrativeClientRegistry.AdministrativeClientView> execute(
      AuthenticatedAdmin actor, String query) {
    if (actor == null || !accessPolicy.isCurrent(actor)) {
      throw new ClientSearchAccessDeniedException();
    }
    if (query == null
        || query.strip().codePointCount(0, query.strip().length()) < 2
        || query.length() > 80) {
      throw new InvalidClientSearchQueryException();
    }
    return clients.search(actor.tenantId(), query.strip(), 21).stream().limit(20).toList();
  }
}
