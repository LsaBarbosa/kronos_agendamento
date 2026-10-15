package com.appagendamento.tenant.infrastructure;

import com.appagendamento.idempotency.api.CommandResultCodec;
import com.appagendamento.idempotency.infrastructure.JacksonCommandResultCodec;
import com.appagendamento.tenant.domain.Tenant;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class TenantCommandResultCodec implements CommandResultCodec<Tenant> {

  private final JacksonCommandResultCodec<Tenant> codec;

  public TenantCommandResultCodec(ObjectMapper objectMapper) {
    codec = new JacksonCommandResultCodec<>(objectMapper, Tenant.class);
  }

  @Override
  public String encode(Tenant result) {
    return codec.encode(result);
  }

  @Override
  public Tenant decode(String json) {
    return codec.decode(json);
  }
}
