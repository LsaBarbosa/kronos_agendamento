package com.appagendamento.appointments.application;

import com.appagendamento.idempotency.application.IdempotencyKey;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ClientAppointmentCancellationDigest {

  public String calculate(
      UUID tenantId, UUID clientId, ClientAppointmentCancellationCommand command) {
    String canonical =
        "appointments.client.cancel.canonical.v1\n"
            + tenantId
            + "\n"
            + clientId
            + "\n"
            + command.appointmentId()
            + "\n"
            + command.expectedVersion();
    return IdempotencyKey.sha256(canonical.getBytes(StandardCharsets.UTF_8));
  }
}
