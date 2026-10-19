package com.appagendamento.clients.api;

import java.util.Optional;
import java.util.UUID;

public interface ClientNotificationContactQuery {

  Optional<String> findE164Phone(UUID tenantId, UUID clientId);
}
