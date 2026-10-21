package com.appagendamento.clients.application;

import com.appagendamento.clients.domain.ClientName;
import com.appagendamento.clients.domain.E164Phone;
import java.util.Optional;
import java.util.UUID;

public interface ClientRepository {

  UUID findOrCreate(UUID tenantId, ClientName displayName, E164Phone normalizedPhone);

  Optional<UUID> findExisting(UUID tenantId, E164Phone normalizedPhone);
}
