package com.appagendamento.availability.infrastructure;

import com.appagendamento.availability.domain.AvailabilityPolicy;
import com.appagendamento.idempotency.api.CommandResultCodec;
import com.appagendamento.idempotency.infrastructure.JacksonCommandResultCodec;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class AvailabilityPolicyCommandResultCodec
    implements CommandResultCodec<AvailabilityPolicy> {

  private final JacksonCommandResultCodec<AvailabilityPolicy> codec;

  public AvailabilityPolicyCommandResultCodec(ObjectMapper objectMapper) {
    codec = new JacksonCommandResultCodec<>(objectMapper, AvailabilityPolicy.class);
  }

  @Override
  public String encode(AvailabilityPolicy result) {
    return codec.encode(result);
  }

  @Override
  public AvailabilityPolicy decode(String json) {
    return codec.decode(json);
  }
}
