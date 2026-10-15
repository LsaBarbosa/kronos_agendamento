package com.appagendamento.idempotency.api;

import java.util.UUID;
import java.util.function.Function;

public interface IdempotencyCoordinator {

  <T> IdempotentCommandResult<T> execute(
      UUID tenantId,
      String commandScope,
      String idempotencyKey,
      String requestDigest,
      Runnable revalidate,
      Function<UUID, T> command,
      CommandResultCodec<T> codec);
}
