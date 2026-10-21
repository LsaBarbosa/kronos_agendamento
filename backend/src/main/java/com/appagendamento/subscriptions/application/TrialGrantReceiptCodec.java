package com.appagendamento.subscriptions.application;

import com.appagendamento.idempotency.api.CommandResultCodec;
import com.appagendamento.idempotency.infrastructure.JacksonCommandResultCodec;
import com.appagendamento.subscriptions.domain.TrialGrantReceipt;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class TrialGrantReceiptCodec implements CommandResultCodec<TrialGrantReceipt> {

  private final JacksonCommandResultCodec<TrialGrantReceipt> delegate;

  public TrialGrantReceiptCodec(ObjectMapper mapper) {
    delegate = new JacksonCommandResultCodec<>(mapper, TrialGrantReceipt.class);
  }

  @Override
  public String encode(TrialGrantReceipt result) {
    return delegate.encode(result);
  }

  @Override
  public TrialGrantReceipt decode(String json) {
    return delegate.decode(json);
  }
}
