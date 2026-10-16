package com.appagendamento.availability.infrastructure;

import com.appagendamento.availability.domain.ScheduleException;
import com.appagendamento.idempotency.api.CommandResultCodec;
import com.appagendamento.idempotency.infrastructure.JacksonCommandResultCodec;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ScheduleExceptionCommandResultCodec implements CommandResultCodec<ScheduleException> {
  private final JacksonCommandResultCodec<ScheduleException> codec;

  public ScheduleExceptionCommandResultCodec(ObjectMapper mapper) {
    codec = new JacksonCommandResultCodec<>(mapper, ScheduleException.class);
  }

  public String encode(ScheduleException value) {
    return codec.encode(value);
  }

  public ScheduleException decode(String json) {
    return codec.decode(json);
  }
}
