package com.appagendamento.publicpage.infrastructure;

import com.appagendamento.idempotency.api.CommandResultCodec;
import com.appagendamento.idempotency.infrastructure.JacksonCommandResultCodec;
import com.appagendamento.publicpage.domain.PublicationState;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class PublicationStateCommandResultCodec implements CommandResultCodec<PublicationState> {

  private final JacksonCommandResultCodec<PublicationState> codec;

  public PublicationStateCommandResultCodec(ObjectMapper objectMapper) {
    codec = new JacksonCommandResultCodec<>(objectMapper, PublicationState.class);
  }

  @Override
  public String encode(PublicationState result) {
    return codec.encode(result);
  }

  @Override
  public PublicationState decode(String json) {
    return codec.decode(json);
  }
}
