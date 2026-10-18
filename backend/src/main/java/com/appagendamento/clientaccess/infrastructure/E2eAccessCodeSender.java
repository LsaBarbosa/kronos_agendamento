package com.appagendamento.clientaccess.infrastructure;

import com.appagendamento.clientaccess.api.AccessCodeSender;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("e2e")
public class E2eAccessCodeSender implements AccessCodeSender {

  private final ConcurrentMap<UUID, String> codes = new ConcurrentHashMap<>();

  @Override
  public DeliveryResult send(UUID tenantId, UUID challengeId, String phone, String code) {
    codes.putIfAbsent(challengeId, code);
    return DeliveryResult.ACCEPTED;
  }

  public String findCode(UUID challengeId) {
    return codes.get(challengeId);
  }
}
