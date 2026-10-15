package com.appagendamento.availability.application;

import com.appagendamento.availability.domain.WeeklyPeriod;
import com.appagendamento.availability.domain.WeeklySchedule;
import com.appagendamento.idempotency.application.IdempotencyKey;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

final class WeeklyScheduleCommandDigest {

  private WeeklyScheduleCommandDigest() {}

  static String calculate(
      UUID tenantId, String timeZone, long expectedVersion, List<WeeklyPeriod> periods) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream command = new DataOutputStream(bytes);
      write(command, "availability.weekly-schedule.replace.canonical.v1");
      write(command, tenantId.toString());
      write(command, timeZone);
      command.writeLong(expectedVersion);
      List<WeeklyPeriod> canonical = WeeklySchedule.canonicalPeriods(periods);
      command.writeInt(canonical.size());
      for (WeeklyPeriod period : canonical) {
        command.writeInt(period.weekday().getValue());
        write(command, period.localStart().toString());
        write(command, period.localEnd().toString());
      }
      command.flush();
      return IdempotencyKey.sha256(bytes.toByteArray());
    } catch (IOException exception) {
      throw new IllegalStateException("Não foi possível canonicalizar a grade", exception);
    }
  }

  private static void write(DataOutputStream output, String value) throws IOException {
    byte[] content = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(content.length);
    output.write(content);
  }
}
