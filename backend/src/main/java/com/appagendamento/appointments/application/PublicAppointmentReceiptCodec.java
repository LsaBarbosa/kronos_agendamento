package com.appagendamento.appointments.application;

import com.appagendamento.idempotency.api.CommandResultCodec;
import com.appagendamento.idempotency.infrastructure.JacksonCommandResultCodec;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class PublicAppointmentReceiptCodec implements CommandResultCodec<PublicAppointmentReceipt> {

  private final JacksonCommandResultCodec<PublicAppointmentReceipt> codec;

  public PublicAppointmentReceiptCodec(ObjectMapper mapper) {
    codec = new JacksonCommandResultCodec<>(mapper, PublicAppointmentReceipt.class);
  }

  @Override
  public String encode(PublicAppointmentReceipt result) {
    return codec.encode(result);
  }

  @Override
  public PublicAppointmentReceipt decode(String json) {
    return codec.decode(json);
  }
}
