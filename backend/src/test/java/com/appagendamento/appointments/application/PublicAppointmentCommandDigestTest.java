package com.appagendamento.appointments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.appagendamento.appointments.api.PublicBookingDigestKeyring;
import com.appagendamento.appointments.domain.PublicBookingDigestKeyUnavailableException;
import com.appagendamento.idempotency.api.CommandDigest;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PublicAppointmentCommandDigestTest {

  private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID SERVICE = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final OffsetDateTime START = OffsetDateTime.parse("2026-10-20T09:00:00-03:00");
  private static final byte[] OLD_KEY = key("old-version-synthetic-key-for-tests-32");
  private static final byte[] NEW_KEY = key("new-version-synthetic-key-for-tests-32");

  @Test
  void hmacIsStableAcrossInstancesAndIncludesAlgorithmVersionAndKeyId() {
    var first = commandDigest(keyring("old-v1", Map.of("old-v1", OLD_KEY)));
    var recreated = commandDigest(keyring("old-v1", Map.of("old-v1", OLD_KEY)));

    assertThat(first.persistedValue()).startsWith("hmac-sha256:v2:old-v1:");
    assertThat(recreated.persistedValue()).isEqualTo(first.persistedValue());
    assertThat(first.persistedValue()).doesNotContain("Ana Silva", "+5511999991234");
  }

  @Test
  void rotatedKeyValidatesOldDigestWithoutChangingItsStoredIdentity() {
    var oldDigest = commandDigest(keyring("old-v1", Map.of("old-v1", OLD_KEY)));
    var rotated = commandDigest(keyring("new-v2", Map.of("old-v1", OLD_KEY, "new-v2", NEW_KEY)));

    assertThat(rotated.persistedValue()).startsWith("hmac-sha256:v2:new-v2:");
    assertThat(rotated.persistedValue()).isNotEqualTo(oldDigest.persistedValue());
    assertThat(rotated.matches(oldDigest.persistedValue())).isTrue();
  }

  @Test
  void missingHistoricalKeyFailsAsAvailabilityNotDigestConflict() {
    var oldDigest = commandDigest(keyring("old-v1", Map.of("old-v1", OLD_KEY)));
    var afterUnsafeRotation = commandDigest(keyring("new-v2", Map.of("new-v2", NEW_KEY)));

    assertThatThrownBy(() -> afterUnsafeRotation.matches(oldDigest.persistedValue()))
        .isInstanceOf(PublicBookingDigestKeyUnavailableException.class);
  }

  @Test
  void legacySha256DigestRemainsReadableWithoutRewrite() {
    var digest = commandDigest(keyring("new-v2", Map.of("new-v2", NEW_KEY)));
    String legacy =
        com.appagendamento.idempotency.application.IdempotencyKey.sha256(legacyCanonicalBytes());

    assertThat(digest.matches(legacy)).isTrue();
    assertThat(digest.persistedValue()).startsWith("hmac-sha256:v2:new-v2:");
  }

  private static CommandDigest commandDigest(PublicBookingDigestKeyring keyring) {
    return new PublicAppointmentCommandDigest(keyring)
        .create(TENANT, "atelie-horizonte", SERVICE, START, "Ana Silva", "+5511999991234");
  }

  private static PublicBookingDigestKeyring keyring(String active, Map<String, byte[]> keys) {
    return new PublicBookingDigestKeyring() {
      @Override
      public String activeKeyId() {
        return active;
      }

      @Override
      public byte[] activeKey() {
        return keys.get(active).clone();
      }

      @Override
      public Optional<byte[]> key(String keyId) {
        return Optional.ofNullable(keys.get(keyId)).map(byte[]::clone);
      }
    };
  }

  private static byte[] key(String value) {
    byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    if (bytes.length < 32) throw new IllegalArgumentException("synthetic test key too short");
    return bytes;
  }

  private static byte[] legacyCanonicalBytes() {
    try {
      var bytes = new java.io.ByteArrayOutputStream();
      var output = new java.io.DataOutputStream(bytes);
      for (String value :
          new String[] {
            "appointments.public.create.canonical.v1",
            TENANT.toString(),
            "atelie-horizonte",
            SERVICE.toString(),
            START.toInstant().toString(),
            START.getOffset().toString(),
            "Ana Silva",
            "+5511999991234"
          }) {
        byte[] field = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        output.writeInt(field.length);
        output.write(field);
      }
      output.flush();
      return bytes.toByteArray();
    } catch (java.io.IOException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
