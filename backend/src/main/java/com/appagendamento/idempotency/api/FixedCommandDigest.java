package com.appagendamento.idempotency.api;

import java.util.Objects;

public record FixedCommandDigest(String persistedValue) implements CommandDigest {

  public FixedCommandDigest {
    Objects.requireNonNull(persistedValue);
  }

  @Override
  public boolean matches(String storedValue) {
    return persistedValue.equals(storedValue);
  }
}
