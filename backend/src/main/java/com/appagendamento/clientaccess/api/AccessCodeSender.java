package com.appagendamento.clientaccess.api;

import java.util.UUID;

public interface AccessCodeSender {

  DeliveryResult send(UUID tenantId, UUID challengeId, String phone, String code);

  enum DeliveryResult {
    ACCEPTED,
    FAILED,
    UNKNOWN,
    UNAVAILABLE
  }
}
