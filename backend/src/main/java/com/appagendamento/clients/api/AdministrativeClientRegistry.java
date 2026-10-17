package com.appagendamento.clients.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdministrativeClientRegistry {

  Optional<AdministrativeClientView> find(UUID tenantId, UUID clientId);

  UUID findOrCreate(UUID tenantId, String name, String phone);

  List<AdministrativeClientView> search(UUID tenantId, String query, int limit);

  record AdministrativeClientView(
      UUID clientId, UUID tenantId, String displayName, String status) {}
}
