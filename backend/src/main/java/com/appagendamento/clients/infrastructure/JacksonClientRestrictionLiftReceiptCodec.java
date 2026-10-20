package com.appagendamento.clients.infrastructure;

import com.appagendamento.clients.application.ClientRestrictionLiftReceipt;
import com.appagendamento.clients.application.ClientRestrictionLiftReceiptCodec;
import com.appagendamento.idempotency.infrastructure.JacksonCommandResultCodec;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class JacksonClientRestrictionLiftReceiptCodec implements ClientRestrictionLiftReceiptCodec {

  private final JacksonCommandResultCodec<ClientRestrictionLiftReceipt> codec;

  public JacksonClientRestrictionLiftReceiptCodec(ObjectMapper mapper) {
    codec = new JacksonCommandResultCodec<>(mapper, ClientRestrictionLiftReceipt.class);
  }

  @Override
  public String encode(ClientRestrictionLiftReceipt value) {
    return codec.encode(value);
  }

  @Override
  public ClientRestrictionLiftReceipt decode(String value) {
    return codec.decode(value);
  }
}
