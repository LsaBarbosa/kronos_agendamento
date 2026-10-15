package com.appagendamento.idempotency.application;

import java.util.UUID;

public final class OperationInProgressException extends RuntimeException {

  private final UUID operationId;

  public OperationInProgressException(UUID operationId) {
    super("Operação idempotente ainda está em andamento");
    this.operationId = operationId;
  }

  public UUID operationId() {
    return operationId;
  }
}
