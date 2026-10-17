package com.appagendamento.clients.api;

import java.util.UUID;

public interface PublicClientRegistry {

  String normalizePhone(String rawPhone);

  String canonicalizeName(String rawName);

  UUID findOrCreate(UUID tenantId, String displayName, String normalizedPhone);
}
