package com.appagendamento.availability.infrastructure;

import com.appagendamento.availability.domain.BlockedPeriod;
import com.appagendamento.idempotency.api.CommandResultCodec;
import com.appagendamento.idempotency.infrastructure.JacksonCommandResultCodec;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class BlockedPeriodCommandResultCodec implements CommandResultCodec<BlockedPeriod> {
  private final JacksonCommandResultCodec<BlockedPeriod> codec;

  public BlockedPeriodCommandResultCodec(ObjectMapper mapper) {
    codec = new JacksonCommandResultCodec<>(mapper, BlockedPeriod.class);
  }

  public String encode(BlockedPeriod value) {
    return codec.encode(value);
  }

  public BlockedPeriod decode(String json) {
    return codec.decode(json);
  }
}
