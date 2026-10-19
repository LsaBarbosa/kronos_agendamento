package com.appagendamento.clientaccess.infrastructure;

import com.appagendamento.clientaccess.api.AccessCodeSender;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!e2e")
public class UnavailableAccessCodeSender implements AccessCodeSender {

  @Override
  public DeliveryResult send(UUID tenantId, UUID challengeId, String phone, String code) {
    return DeliveryResult.UNAVAILABLE;
  }
}
