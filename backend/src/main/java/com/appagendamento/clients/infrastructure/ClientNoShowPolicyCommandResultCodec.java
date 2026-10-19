package com.appagendamento.clients.infrastructure;

import com.appagendamento.clients.domain.ClientNoShowPolicy;
import com.appagendamento.idempotency.api.CommandResultCodec;
import com.appagendamento.idempotency.infrastructure.JacksonCommandResultCodec;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ClientNoShowPolicyCommandResultCodec
    implements CommandResultCodec<ClientNoShowPolicy> {

  private final JacksonCommandResultCodec<ClientNoShowPolicy> codec;

  public ClientNoShowPolicyCommandResultCodec(ObjectMapper mapper) {
    codec = new JacksonCommandResultCodec<>(mapper, ClientNoShowPolicy.class);
  }

  @Override
  public String encode(ClientNoShowPolicy result) {
    return codec.encode(result);
  }

  @Override
  public ClientNoShowPolicy decode(String json) {
    return codec.decode(json);
  }
}
