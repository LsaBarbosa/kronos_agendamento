package com.appagendamento.publicpage.domain;

import java.util.UUID;

public record PublicationState(
    UUID tenantId, String contactPhone, boolean published, long version) {

  public PublicationState {
    if (tenantId == null || version < 0) {
      throw new IllegalArgumentException("Estado de publicação inválido");
    }
    if (contactPhone != null) {
      contactPhone = contactPhone.strip();
      if (contactPhone.length() < 6 || contactPhone.length() > 32) {
        throw new IllegalArgumentException("Telefone de contato inválido");
      }
    }
  }

  public PublicationState withContactPhone(String value) {
    return new PublicationState(tenantId, value, published, version + 1);
  }

  public PublicationState withPublished(boolean value) {
    return new PublicationState(tenantId, contactPhone, value, version + 1);
  }
}
