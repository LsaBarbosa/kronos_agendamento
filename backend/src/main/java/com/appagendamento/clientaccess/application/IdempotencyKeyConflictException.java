package com.appagendamento.clientaccess.application;

public class IdempotencyKeyConflictException extends RuntimeException {

  public IdempotencyKeyConflictException() {
    super("Chave de operação já utilizada.");
  }
}
