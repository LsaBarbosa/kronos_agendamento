package com.appagendamento.appointments.application;

import com.appagendamento.appointments.domain.AppointmentOutcome;
import com.appagendamento.idempotency.application.IdempotencyKey;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
final class AdministrativeAppointmentOutcomeDigest {

  String calculate(
      UUID tenantId,
      AppointmentOutcome outcome,
      AdministrativeAppointmentOutcomeCommand command,
      String normalizedReason) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream canonical = new DataOutputStream(bytes);
      write(canonical, "appointments.admin.outcome.canonical.v1");
      write(canonical, tenantId.toString());
      write(canonical, outcome.name());
      write(canonical, command.appointmentId().toString());
      canonical.writeLong(command.expectedVersion());
      write(canonical, normalizedReason == null ? "" : normalizedReason);
      canonical.flush();
      return IdempotencyKey.sha256(bytes.toByteArray());
    } catch (IOException exception) {
      throw new IllegalStateException("Não foi possível canonicalizar o comando", exception);
    }
  }

  private static void write(DataOutputStream output, String value) throws IOException {
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(encoded.length);
    output.write(encoded);
  }
}
