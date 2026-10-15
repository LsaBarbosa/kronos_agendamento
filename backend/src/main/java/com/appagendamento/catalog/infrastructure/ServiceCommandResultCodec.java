package com.appagendamento.catalog.infrastructure;

import com.appagendamento.catalog.domain.Service;
import com.appagendamento.idempotency.api.CommandResultCodec;
import com.appagendamento.idempotency.infrastructure.JacksonCommandResultCodec;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ServiceCommandResultCodec implements CommandResultCodec<Service> {

  private final JacksonCommandResultCodec<Service> codec;

  public ServiceCommandResultCodec(ObjectMapper objectMapper) {
    codec = new JacksonCommandResultCodec<>(objectMapper, Service.class);
  }

  @Override
  public String encode(Service result) {
    return codec.encode(result);
  }

  @Override
  public Service decode(String json) {
    return codec.decode(json);
  }
}
