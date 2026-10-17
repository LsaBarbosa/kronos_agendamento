package com.appagendamento.appointments.application;

import com.appagendamento.idempotency.api.CommandResultCodec;
import com.appagendamento.idempotency.infrastructure.JacksonCommandResultCodec;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class AdministrativeAppointmentReceiptCodec
    implements CommandResultCodec<AdministrativeAppointmentReceipt> {

  private final JacksonCommandResultCodec<AdministrativeAppointmentReceipt> codec;

  public AdministrativeAppointmentReceiptCodec(ObjectMapper mapper) {
    codec = new JacksonCommandResultCodec<>(mapper, AdministrativeAppointmentReceipt.class);
  }

  @Override
  public String encode(AdministrativeAppointmentReceipt result) {
    return codec.encode(result);
  }

  @Override
  public AdministrativeAppointmentReceipt decode(String json) {
    return codec.decode(json);
  }
}
