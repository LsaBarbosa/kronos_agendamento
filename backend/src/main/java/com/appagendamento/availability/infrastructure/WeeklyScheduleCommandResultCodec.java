package com.appagendamento.availability.infrastructure;

import com.appagendamento.availability.domain.WeeklySchedule;
import com.appagendamento.idempotency.api.CommandResultCodec;
import com.appagendamento.idempotency.infrastructure.JacksonCommandResultCodec;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class WeeklyScheduleCommandResultCodec implements CommandResultCodec<WeeklySchedule> {

  private final JacksonCommandResultCodec<WeeklySchedule> codec;

  public WeeklyScheduleCommandResultCodec(ObjectMapper objectMapper) {
    codec = new JacksonCommandResultCodec<>(objectMapper, WeeklySchedule.class);
  }

  @Override
  public String encode(WeeklySchedule result) {
    return codec.encode(result);
  }

  @Override
  public WeeklySchedule decode(String json) {
    return codec.decode(json);
  }
}
