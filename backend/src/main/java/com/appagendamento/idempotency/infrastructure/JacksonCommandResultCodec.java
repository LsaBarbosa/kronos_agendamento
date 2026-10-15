package com.appagendamento.idempotency.infrastructure;

import com.appagendamento.idempotency.api.CommandResultCodec;
import tools.jackson.databind.ObjectMapper;

public final class JacksonCommandResultCodec<T> implements CommandResultCodec<T> {

  private final ObjectMapper mapper;
  private final Class<T> resultType;

  public JacksonCommandResultCodec(ObjectMapper mapper, Class<T> resultType) {
    this.mapper = mapper;
    this.resultType = resultType;
  }

  @Override
  public String encode(T result) {
    return mapper.writeValueAsString(result);
  }

  @Override
  public T decode(String json) {
    return mapper.readValue(json, resultType);
  }
}
