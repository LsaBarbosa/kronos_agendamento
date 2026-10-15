package com.appagendamento.idempotency.api;

import java.util.UUID;

public record IdempotentCommandResult<T>(UUID operationId, T value, boolean replayed) {}
