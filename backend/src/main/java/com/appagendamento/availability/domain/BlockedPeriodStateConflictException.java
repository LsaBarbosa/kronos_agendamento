package com.appagendamento.availability.domain;

public class BlockedPeriodStateConflictException extends RuntimeException {

  public BlockedPeriodStateConflictException() {
    super("O bloqueio não está ativo");
  }
}
