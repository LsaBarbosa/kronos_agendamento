package com.appagendamento.idempotency.api;

import java.util.UUID;
import java.util.function.Function;

public interface IdempotencyCoordinator {

  default <T> IdempotentCommandResult<T> execute(
      UUID tenantId,
      String commandScope,
      String idempotencyKey,
      String requestDigest,
      Runnable revalidate,
      Function<UUID, T> command,
      CommandResultCodec<T> codec) {
    return execute(
        tenantId,
        commandScope,
        idempotencyKey,
        new FixedCommandDigest(requestDigest),
        revalidate,
        command,
        codec);
  }

  <T> IdempotentCommandResult<T> execute(
      UUID tenantId,
      String commandScope,
      String idempotencyKey,
      CommandDigest requestDigest,
      Runnable revalidate,
      Function<UUID, T> command,
      CommandResultCodec<T> codec);
}
