package com.appagendamento.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.appagendamento.catalog.domain.Service;
import com.appagendamento.catalog.domain.ServiceStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ServiceDomainTest {

  @Test
  void canonicalizesNamesAndValidatesDurationAndBuffer() {
    Service service =
        Service.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "  Massagem  ",
            "  Terapêutica  ",
            60,
            0,
            ServiceStatus.INACTIVE,
            Instant.parse("2026-01-01T00:00:00Z"));

    assertThat(service.name()).isEqualTo("Massagem");
    assertThat(service.normalizedName()).isEqualTo("massagem");
    assertThat(service.description()).isEqualTo("Terapêutica");
    assertThat(service.bufferAfterMinutes()).isZero();
    assertThat(Service.normalizeForComparison("ＭＡＳＳＡＧＥＭ"))
        .isEqualTo(Service.normalizeForComparison("Massagem"));
    assertThatThrownBy(() -> create(0, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> create(20, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> create(15, -15)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> create(15, 10)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void updateAndStatusChangesAdvanceVersionAndPreserveStableIdentity() {
    Service original = create(30, 15);
    Service updated = original.update("Corte", "", 45, null, Instant.parse("2026-01-02T00:00:00Z"));
    Service inactive =
        updated.withStatus(ServiceStatus.INACTIVE, Instant.parse("2026-01-03T00:00:00Z"));

    assertThat(updated.serviceId()).isEqualTo(original.serviceId());
    assertThat(updated.name()).isEqualTo("Corte");
    assertThat(updated.description()).isNull();
    assertThat(updated.durationMinutes()).isEqualTo(45);
    assertThat(updated.bufferAfterMinutes()).isEqualTo(15);
    assertThat(updated.version()).isEqualTo(1);
    assertThat(inactive.status()).isEqualTo(ServiceStatus.INACTIVE);
    assertThat(inactive.version()).isEqualTo(2);
    assertThat(inactive.withStatus(ServiceStatus.INACTIVE, Instant.now())).isSameAs(inactive);
  }

  private static Service create(int duration, int buffer) {
    return Service.create(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "Serviço",
        null,
        duration,
        buffer,
        ServiceStatus.ACTIVE,
        Instant.parse("2026-01-01T00:00:00Z"));
  }
}
