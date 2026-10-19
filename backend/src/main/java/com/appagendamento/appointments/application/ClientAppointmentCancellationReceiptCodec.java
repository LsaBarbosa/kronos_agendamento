package com.appagendamento.appointments.application;

import com.appagendamento.idempotency.api.CommandResultCodec;
import com.appagendamento.idempotency.infrastructure.JacksonCommandResultCodec;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ClientAppointmentCancellationReceiptCodec
    implements CommandResultCodec<ClientAppointmentCancellationReceipt> {

  private final JacksonCommandResultCodec<ClientAppointmentCancellationReceipt> codec;

  public ClientAppointmentCancellationReceiptCodec(ObjectMapper mapper) {
    codec = new JacksonCommandResultCodec<>(mapper, ClientAppointmentCancellationReceipt.class);
  }

  @Override
  public String encode(ClientAppointmentCancellationReceipt result) {
    return codec.encode(result);
  }

  @Override
  public ClientAppointmentCancellationReceipt decode(String json) {
    return codec.decode(json);
  }
}
