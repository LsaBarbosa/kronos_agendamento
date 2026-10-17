package com.appagendamento.idempotency.api;

public interface CommandDigest {

  String persistedValue();

  boolean matches(String storedValue);
}
