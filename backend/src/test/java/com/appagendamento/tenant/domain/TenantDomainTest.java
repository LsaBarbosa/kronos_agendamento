package com.appagendamento.tenant.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantDomainTest {

  @Test
  void canonicalizesSlugWithoutTransliteratingOrChangingWordBoundaries() {
    assertThat(new TenantSlug("  Acme-Store ").value()).isEqualTo("acme-store");
    assertThat(new TenantSlug("ＡＣＭＥ").value()).isEqualTo("acme");
  }

  @Test
  void rejectsInvalidSlugAndNonIanaTimezone() {
    assertThatIllegalArgumentException().isThrownBy(() -> new TenantSlug("acme store"));
    assertThatIllegalArgumentException().isThrownBy(() -> new TenantSlug("-acme"));
    assertThatIllegalArgumentException().isThrownBy(() -> new TenantSlug("acme--store"));
    assertThatIllegalArgumentException().isThrownBy(() -> tenant("America/Sao_Paulo_offset"));
  }

  @Test
  void storesIanaTimezoneAndIncrementsVersionWhenRenamed() {
    Tenant tenant = Tenant.create(UUID.randomUUID(), " Clínica Acme ", "Acme", "America/Sao_Paulo");

    assertThat(tenant.name()).isEqualTo("Clínica Acme");
    assertThat(tenant.slug().value()).isEqualTo("acme");
    assertThat(tenant.status()).isEqualTo(TenantStatus.ACTIVE);
    assertThat(tenant.rename("Acme Saúde").version()).isEqualTo(1);
  }

  private static Tenant tenant(String timezone) {
    return new Tenant(
        UUID.randomUUID(),
        "Acme",
        new TenantSlug("acme"),
        timezone,
        TenantStatus.ACTIVE,
        Instant.EPOCH,
        0);
  }
}
