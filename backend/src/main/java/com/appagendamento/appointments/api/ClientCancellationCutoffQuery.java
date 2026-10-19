package com.appagendamento.appointments.api;

import java.util.OptionalInt;
import java.util.UUID;

public interface ClientCancellationCutoffQuery {

  OptionalInt cancellationCutoffHours(UUID tenantId);
}
