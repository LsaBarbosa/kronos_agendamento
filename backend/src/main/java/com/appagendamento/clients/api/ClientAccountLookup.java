package com.appagendamento.clients.api;

import java.util.Optional;
import java.util.UUID;

public interface ClientAccountLookup {

  Optional<UUID> findClientId(UUID tenantId, String normalizedPhone);
}
