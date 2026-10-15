package com.appagendamento.idempotency.api;

public interface CommandResultCodec<T> {

  String encode(T result);

  T decode(String json);
}
