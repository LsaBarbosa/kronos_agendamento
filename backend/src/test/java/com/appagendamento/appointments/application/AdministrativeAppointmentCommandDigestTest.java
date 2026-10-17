package com.appagendamento.appointments.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.appagendamento.appointments.domain.AdministrativeOverridePolicy;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdministrativeAppointmentCommandDigestTest {

  private final AdministrativeAppointmentCommandDigest digests =
      new AdministrativeAppointmentCommandDigest();

  @Test
  void canonicalizesOverrideOrderAndBindsTargetScheduleClientAndReason() {
    UUID tenantId = UUID.randomUUID();
    UUID serviceId = UUID.randomUUID();
    OffsetDateTime startsAt = OffsetDateTime.parse("2026-10-26T08:00-03:00");
    String name = "Cliente Admin";
    String phone = "+5511999991234";
    String reason = "Encaixe autorizado";
    var first =
        command(
            serviceId,
            startsAt,
            List.of(
                AdministrativeOverridePolicy.MIN_NOTICE,
                AdministrativeOverridePolicy.OUTSIDE_WEEKLY_SCHEDULE),
            reason,
            8);
    var reordered =
        command(
            serviceId,
            startsAt,
            List.of(
                AdministrativeOverridePolicy.OUTSIDE_WEEKLY_SCHEDULE,
                AdministrativeOverridePolicy.MIN_NOTICE),
            reason,
            8);

    String digest = digests.calculate(tenantId, first, name, phone, reason);

    assertThat(digests.calculate(tenantId, reordered, name, phone, reason)).isEqualTo(digest);
    assertThat(digests.calculate(UUID.randomUUID(), first, name, phone, reason))
        .isNotEqualTo(digest);
    assertThat(
            digests.calculate(
                tenantId,
                command(UUID.randomUUID(), startsAt, first.overridePolicies(), reason, 8),
                name,
                phone,
                reason))
        .isNotEqualTo(digest);
    assertThat(
            digests.calculate(
                tenantId,
                command(serviceId, startsAt, first.overridePolicies(), reason, 9),
                name,
                phone,
                reason))
        .isNotEqualTo(digest);
    assertThat(digests.calculate(tenantId, first, name, phone, "Outro motivo"))
        .isNotEqualTo(digest);
    assertThat(digests.calculate(tenantId, first, name, "+5511999991235", reason))
        .isNotEqualTo(digest);
  }

  private static CreateAdministrativeAppointmentCommand command(
      UUID serviceId,
      OffsetDateTime startsAt,
      List<AdministrativeOverridePolicy> overrides,
      String reason,
      long revision) {
    return new CreateAdministrativeAppointmentCommand(
        serviceId, startsAt, null, "Cliente Admin", "+5511999991234", overrides, reason, revision);
  }
}
