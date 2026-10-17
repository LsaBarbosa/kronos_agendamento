package com.appagendamento.appointments.application;

import com.appagendamento.appointments.domain.AdministrativeOverridePolicy;
import com.appagendamento.idempotency.application.IdempotencyKey;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
final class AdministrativeAppointmentCommandDigest {

  String calculate(
      UUID tenantId,
      CreateAdministrativeAppointmentCommand command,
      String name,
      String phone,
      String reason) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream canonical = new DataOutputStream(bytes);
      write(canonical, "appointments.admin.create.canonical.v1");
      write(canonical, tenantId.toString());
      write(canonical, command.serviceId().toString());
      write(canonical, command.startsAt().toInstant().toString());
      write(canonical, command.startsAt().getOffset().toString());
      write(canonical, command.clientId() == null ? "NEW_CLIENT" : "CLIENT:" + command.clientId());
      write(canonical, name == null ? "" : name);
      write(canonical, phone == null ? "" : phone);
      canonical.writeLong(command.expectedScheduleVersion());
      List<AdministrativeOverridePolicy> policies =
          command.overridePolicies().stream().sorted().toList();
      canonical.writeInt(policies.size());
      for (AdministrativeOverridePolicy policy : policies) write(canonical, policy.name());
      write(canonical, reason == null ? "" : reason);
      canonical.flush();
      return IdempotencyKey.sha256(bytes.toByteArray());
    } catch (IOException exception) {
      throw new IllegalStateException(
          "Não foi possível canonicalizar o comando administrativo", exception);
    }
  }

  private static void write(DataOutputStream output, String value) throws IOException {
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(encoded.length);
    output.write(encoded);
  }
}
