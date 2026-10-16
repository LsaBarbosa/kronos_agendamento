package com.appagendamento.availability.infrastructure;

import com.appagendamento.availability.application.ManageScheduleExceptions.ExceptionRemovalReceipt;
import com.appagendamento.idempotency.api.CommandResultCodec;
import com.appagendamento.idempotency.infrastructure.JacksonCommandResultCodec;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ExceptionRemovalCommandResultCodec
    implements CommandResultCodec<ExceptionRemovalReceipt> {
  private final JacksonCommandResultCodec<ExceptionRemovalReceipt> codec;

  public ExceptionRemovalCommandResultCodec(ObjectMapper mapper) {
    codec = new JacksonCommandResultCodec<>(mapper, ExceptionRemovalReceipt.class);
  }

  public String encode(ExceptionRemovalReceipt value) {
    return codec.encode(value);
  }

  public ExceptionRemovalReceipt decode(String json) {
    return codec.decode(json);
  }
}
