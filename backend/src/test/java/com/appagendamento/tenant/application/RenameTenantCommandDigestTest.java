package com.appagendamento.tenant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.appagendamento.idempotency.application.IdempotencyKey;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RenameTenantCommandDigestTest {

  @Test
  void canonicalizationIsStableAndIncludesTenantTargetVersionAndValidatedName() {
    UUID tenant = UUID.randomUUID();
    UUID target = UUID.randomUUID();
    String digest = RenameTenantCommandDigest.calculate(tenant, target, "Nome", 4);

    assertThat(RenameTenantCommandDigest.calculate(tenant, target, "Nome", 4)).isEqualTo(digest);
    assertThat(RenameTenantCommandDigest.calculate(UUID.randomUUID(), target, "Nome", 4))
        .isNotEqualTo(digest);
    assertThat(RenameTenantCommandDigest.calculate(tenant, UUID.randomUUID(), "Nome", 4))
        .isNotEqualTo(digest);
    assertThat(RenameTenantCommandDigest.calculate(tenant, target, "Nome", 5)).isNotEqualTo(digest);
    assertThat(RenameTenantCommandDigest.calculate(tenant, target, "Outro", 4))
        .isNotEqualTo(digest);
  }

  @Test
  void idempotencyKeyIsHashedAndLengthLimited() {
    String key = "opaque-client-key";
    String digest = IdempotencyKey.digest(key);

    assertThat(digest).hasSize(64).doesNotContain(key);
    assertThat(IdempotencyKey.digest(key)).isEqualTo(digest);
  }
}
