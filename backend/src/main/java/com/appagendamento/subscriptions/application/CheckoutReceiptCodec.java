package com.appagendamento.subscriptions.application;

import com.appagendamento.idempotency.api.CommandResultCodec;
import com.appagendamento.idempotency.infrastructure.JacksonCommandResultCodec;
import com.appagendamento.subscriptions.domain.CheckoutReceipt;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class CheckoutReceiptCodec implements CommandResultCodec<CheckoutReceipt> {

  private final JacksonCommandResultCodec<CheckoutReceipt> delegate;

  public CheckoutReceiptCodec(ObjectMapper mapper) {
    delegate = new JacksonCommandResultCodec<>(mapper, CheckoutReceipt.class);
  }

  @Override
  public String encode(CheckoutReceipt result) {
    return delegate.encode(result);
  }

  @Override
  public CheckoutReceipt decode(String json) {
    return delegate.decode(json);
  }
}
