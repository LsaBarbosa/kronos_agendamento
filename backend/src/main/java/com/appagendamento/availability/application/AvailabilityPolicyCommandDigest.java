package com.appagendamento.availability.application;

import com.appagendamento.availability.domain.AvailabilityPolicy;
import com.appagendamento.idempotency.application.IdempotencyKey;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class AvailabilityPolicyCommandDigest {

  private AvailabilityPolicyCommandDigest() {}

  static String calculate(UUID tenantId, AvailabilityPolicy policy, long expectedVersion) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream command = new DataOutputStream(bytes);
      write(command, "availability.policy.replace.canonical.v1");
      write(command, tenantId.toString());
      command.writeLong(expectedVersion);
      command.writeInt(policy.slotStepMinutes());
      command.writeInt(policy.minimumNoticeHours());
      command.writeInt(policy.bookingHorizonDays());
      command.writeInt(policy.cancellationCutoffHours());
      command.writeInt(policy.reminderOffsetHours());
      command.flush();
      return IdempotencyKey.sha256(bytes.toByteArray());
    } catch (IOException exception) {
      throw new IllegalStateException("Não foi possível canonicalizar a política", exception);
    }
  }

  private static void write(DataOutputStream output, String value) throws IOException {
    byte[] content = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(content.length);
    output.write(content);
  }
}
