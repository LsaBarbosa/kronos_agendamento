package com.appagendamento.appointments.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.appagendamento.appointments.infrastructure.ConfiguredPublicBookingDigestKeyring;
import com.appagendamento.idempotency.api.CommandResultCodec;
import com.appagendamento.idempotency.api.IdempotencyCoordinator;
import com.appagendamento.support.PostgresTestEnvironment;
import com.appagendamento.tenant.application.CreateTenant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class PublicAppointmentDigestReplayIT {

  private static final byte[] OLD_KEY = key("synthetic-old-version-key-for-rotation-it");
  private static final byte[] NEW_KEY = key("synthetic-new-version-key-for-rotation-it");
  private static final String SCOPE = "appointments.public.create.v1";

  @Autowired private IdempotencyCoordinator coordinator;
  @Autowired private CreateTenant createTenant;
  @Autowired private JdbcTemplate jdbc;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    PostgresTestEnvironment.configure(registry);
  }

  @Test
  void replaysPersistedDigestAfterActiveKeyRotationWhileHistoricalKeyRemainsAvailable() {
    var tenant = createTenant.execute("HMAC replay", "hmac-replay-" + UUID.randomUUID(), "UTC");
    UUID serviceId = UUID.randomUUID();
    OffsetDateTime startsAt = OffsetDateTime.parse("2026-10-20T09:00:00-03:00");
    String key = "hmac-rotation-" + UUID.randomUUID();
    var oldKeyring = keyring("old-v1", "old-v1=" + encoded(OLD_KEY));
    var rotatedKeyring =
        keyring("new-v2", "old-v1=" + encoded(OLD_KEY) + ";new-v2=" + encoded(NEW_KEY));
    var oldDigest =
        new PublicAppointmentCommandDigest(oldKeyring)
            .create(
                tenant.tenantId(),
                tenant.slug().value(),
                serviceId,
                startsAt,
                "Visitante",
                "+5511999991234");
    var replayDigest =
        new PublicAppointmentCommandDigest(rotatedKeyring)
            .create(
                tenant.tenantId(),
                tenant.slug().value(),
                serviceId,
                startsAt,
                "Visitante",
                "+5511999991234");
    AtomicInteger executions = new AtomicInteger();

    var original =
        coordinator.execute(
            tenant.tenantId(),
            SCOPE,
            key,
            oldDigest,
            () -> {},
            operationId -> {
              executions.incrementAndGet();
              return "receipt:" + operationId;
            },
            stringCodec());
    String storedDigest =
        jdbc.queryForObject(
            "SELECT request_digest FROM agendamento.idempotency_record WHERE tenant_id = ? AND command_scope = ? AND key_digest = ?",
            String.class,
            tenant.tenantId(),
            SCOPE,
            com.appagendamento.idempotency.application.IdempotencyKey.digest(key));
    var replay =
        coordinator.execute(
            tenant.tenantId(),
            SCOPE,
            key,
            replayDigest,
            () -> {},
            operationId -> {
              executions.incrementAndGet();
              return "unexpected-new-receipt";
            },
            stringCodec());

    assertThat(storedDigest).isEqualTo(oldDigest.persistedValue());
    assertThat(storedDigest).startsWith("hmac-sha256:v2:old-v1:");
    assertThat(replayDigest.persistedValue()).startsWith("hmac-sha256:v2:new-v2:");
    assertThat(original.operationId()).isEqualTo(replay.operationId());
    assertThat(replay.value()).isEqualTo(original.value());
    assertThat(replay.replayed()).isTrue();
    assertThat(executions).hasValue(1);
  }

  private static ConfiguredPublicBookingDigestKeyring keyring(String active, String keys) {
    return new ConfiguredPublicBookingDigestKeyring(active, keys);
  }

  private static CommandResultCodec<String> stringCodec() {
    return new CommandResultCodec<>() {
      @Override
      public String encode(String result) {
        return "\"" + result + "\"";
      }

      @Override
      public String decode(String json) {
        return json.substring(1, json.length() - 1);
      }
    };
  }

  private static byte[] key(String value) {
    return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  private static String encoded(byte[] value) {
    return Base64.getEncoder().encodeToString(value);
  }
}
