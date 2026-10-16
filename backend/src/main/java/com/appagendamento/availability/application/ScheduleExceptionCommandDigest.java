package com.appagendamento.availability.application;

import com.appagendamento.availability.domain.ScheduleException;
import com.appagendamento.availability.domain.SpecialPeriod;
import com.appagendamento.idempotency.application.IdempotencyKey;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

final class ScheduleExceptionCommandDigest {

  private ScheduleExceptionCommandDigest() {}

  static String calculate(
      UUID tenantId,
      String scope,
      UUID targetId,
      String timeZone,
      long expectedVersion,
      ScheduleException scheduleException) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream command = new DataOutputStream(bytes);
      write(command, scope + ".canonical.v1");
      write(command, tenantId.toString());
      write(command, targetId == null ? "" : targetId.toString());
      write(command, timeZone);
      command.writeLong(expectedVersion);
      write(command, scheduleException.localDate().toString());
      write(command, scheduleException.kind().name());
      List<SpecialPeriod> periods = ScheduleException.canonicalPeriods(scheduleException.periods());
      command.writeInt(periods.size());
      for (SpecialPeriod period : periods) {
        write(command, period.localStart().toString());
        write(command, period.localEnd().toString());
      }
      write(command, scheduleException.reason() == null ? "" : scheduleException.reason());
      command.flush();
      return IdempotencyKey.sha256(bytes.toByteArray());
    } catch (IOException exception) {
      throw new IllegalStateException("Não foi possível canonicalizar a exceção", exception);
    }
  }

  static String calculateRemoval(UUID tenantId, UUID exceptionId, long expectedVersion) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream command = new DataOutputStream(bytes);
      write(command, "availability.schedule-exception.remove.canonical.v1");
      write(command, tenantId.toString());
      write(command, exceptionId.toString());
      command.writeLong(expectedVersion);
      command.flush();
      return IdempotencyKey.sha256(bytes.toByteArray());
    } catch (IOException exception) {
      throw new IllegalStateException("Não foi possível canonicalizar a remoção", exception);
    }
  }

  private static void write(DataOutputStream output, String value) throws IOException {
    byte[] content = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(content.length);
    output.write(content);
  }
}
