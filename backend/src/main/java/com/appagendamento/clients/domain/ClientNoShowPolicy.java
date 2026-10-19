package com.appagendamento.clients.domain;

import java.util.UUID;

public record ClientNoShowPolicy(UUID tenantId, int threshold, long version) {

  public ClientNoShowPolicy {
    if (tenantId == null || threshold < 1 || threshold > 5 || version < 1) {
      throw new IllegalArgumentException("Política de faltas inválida");
    }
  }
}
