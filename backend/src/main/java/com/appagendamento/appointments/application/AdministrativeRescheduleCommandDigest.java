package com.appagendamento.appointments.application;

import com.appagendamento.appointments.domain.AdministrativeOverridePolicy;
import com.appagendamento.idempotency.application.IdempotencyKey;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
final class AdministrativeRescheduleCommandDigest {

  String calculate(
      UUID tenantId, RescheduleAdministrativeAppointmentCommand command, String reason) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream canonical = new DataOutputStream(bytes);
      write(canonical, "appointments.admin.reschedule.canonical.v1");
      write(canonical, tenantId.toString());
      write(canonical, command.appointmentId().toString());
      write(canonical, command.startsAt().toInstant().toString());
      write(canonical, command.startsAt().getOffset().toString());
      canonical.writeLong(command.expectedVersion());
      canonical.writeLong(command.expectedScheduleRevision());
      var policies = command.overridePolicies().stream().sorted().toList();
      canonical.writeInt(policies.size());
      for (AdministrativeOverridePolicy policy : policies) write(canonical, policy.name());
      write(canonical, reason == null ? "" : reason);
      canonical.flush();
      return IdempotencyKey.sha256(bytes.toByteArray());
    } catch (IOException exception) {
      throw new IllegalStateException("Não foi possível canonicalizar a remarcação", exception);
    }
  }

  private static void write(DataOutputStream output, String value) throws IOException {
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(encoded.length);
    output.write(encoded);
  }
}
