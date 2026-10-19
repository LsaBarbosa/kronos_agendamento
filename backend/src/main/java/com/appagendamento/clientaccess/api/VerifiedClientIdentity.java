package com.appagendamento.clientaccess.api;

import java.util.UUID;

public record VerifiedClientIdentity(UUID tenantId, String normalizedPhone) {

  public VerifiedClientIdentity {
    if (tenantId == null
        || normalizedPhone == null
        || !normalizedPhone.matches("\\+[1-9][0-9]{7,14}")) {
      throw new IllegalArgumentException("Identidade de cliente verificada inválida");
    }
  }
}
