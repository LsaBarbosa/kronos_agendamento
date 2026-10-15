package com.appagendamento.idempotency.application;

public final class IdempotencyKeyReusedException extends RuntimeException {

  public IdempotencyKeyReusedException() {
    super("Idempotency-Key já vinculada a outro comando");
  }
}
