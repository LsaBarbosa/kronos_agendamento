package com.appagendamento.appointments.application;

import com.appagendamento.idempotency.api.CommandResultCodec;
import com.appagendamento.idempotency.infrastructure.JacksonCommandResultCodec;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class AdministrativeAppointmentOutcomeReceiptCodec
    implements CommandResultCodec<AdministrativeAppointmentOutcomeReceipt> {

  private final JacksonCommandResultCodec<AdministrativeAppointmentOutcomeReceipt> codec;

  public AdministrativeAppointmentOutcomeReceiptCodec(ObjectMapper mapper) {
    codec = new JacksonCommandResultCodec<>(mapper, AdministrativeAppointmentOutcomeReceipt.class);
  }

  @Override
  public String encode(AdministrativeAppointmentOutcomeReceipt result) {
    return codec.encode(result);
  }

  @Override
  public AdministrativeAppointmentOutcomeReceipt decode(String json) {
    return codec.decode(json);
  }
}
