package com.appagendamento.appointments.application;

import com.appagendamento.idempotency.api.CommandResultCodec;
import com.appagendamento.idempotency.infrastructure.JacksonCommandResultCodec;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class AdministrativeRescheduleReceiptCodec
    implements CommandResultCodec<AdministrativeRescheduleReceipt> {

  private final JacksonCommandResultCodec<AdministrativeRescheduleReceipt> codec;

  public AdministrativeRescheduleReceiptCodec(ObjectMapper mapper) {
    codec = new JacksonCommandResultCodec<>(mapper, AdministrativeRescheduleReceipt.class);
  }

  @Override
  public String encode(AdministrativeRescheduleReceipt result) {
    return codec.encode(result);
  }

  @Override
  public AdministrativeRescheduleReceipt decode(String json) {
    return codec.decode(json);
  }
}
