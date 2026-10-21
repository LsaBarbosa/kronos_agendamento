package com.appagendamento.clients.infrastructure;

import com.appagendamento.clients.application.ClientRestrictionReconciliationReceipt;
import com.appagendamento.clients.application.ClientRestrictionReconciliationReceiptCodec;
import com.appagendamento.idempotency.infrastructure.JacksonCommandResultCodec;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class JacksonClientRestrictionReconciliationReceiptCodec
    implements ClientRestrictionReconciliationReceiptCodec {

  private final JacksonCommandResultCodec<ClientRestrictionReconciliationReceipt> codec;

  public JacksonClientRestrictionReconciliationReceiptCodec(ObjectMapper mapper) {
    codec = new JacksonCommandResultCodec<>(mapper, ClientRestrictionReconciliationReceipt.class);
  }

  @Override
  public String encode(ClientRestrictionReconciliationReceipt value) {
    return codec.encode(value);
  }

  @Override
  public ClientRestrictionReconciliationReceipt decode(String value) {
    return codec.decode(value);
  }
}
