package com.appagendamento.catalog.application;

import com.appagendamento.catalog.domain.Service;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServiceRepository {

  Service insert(Service service);

  Optional<Service> findByTenantAndId(UUID tenantId, UUID serviceId);

  List<Service> findAllByTenant(UUID tenantId);

  boolean update(Service service, long expectedVersion);
}
