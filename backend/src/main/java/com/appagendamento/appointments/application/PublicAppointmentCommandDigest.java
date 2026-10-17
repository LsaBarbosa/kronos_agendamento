package com.appagendamento.appointments.application;

import com.appagendamento.appointments.api.PublicBookingDigestKeyring;
import com.appagendamento.appointments.domain.PublicBookingDigestKeyUnavailableException;
import com.appagendamento.idempotency.api.CommandDigest;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
final class PublicAppointmentCommandDigest {

  private static final String LEGACY_VERSION = "appointments.public.create.canonical.v1";
  private static final String CURRENT_VERSION = "appointments.public.create.canonical.v2";
  private final PublicBookingDigestKeyring keyring;

  PublicAppointmentCommandDigest(PublicBookingDigestKeyring keyring) {
    this.keyring = keyring;
  }

  CommandDigest create(
      UUID tenantId,
      String slug,
      UUID serviceId,
      OffsetDateTime startsAt,
      String canonicalName,
      String normalizedPhone) {
    try {
      byte[] legacyCanonical =
          canonical(
              LEGACY_VERSION, tenantId, slug, serviceId, startsAt, canonicalName, normalizedPhone);
      byte[] currentCanonical =
          canonical(
              CURRENT_VERSION, tenantId, slug, serviceId, startsAt, canonicalName, normalizedPhone);
      String keyId = keyring.activeKeyId();
      String encodedDigest =
          "hmac-sha256:v2:" + keyId + ":" + hmac(keyring.activeKey(), currentCanonical);
      return new PublicCommandDigest(encodedDigest, legacyCanonical, currentCanonical, keyring);
    } catch (IOException | NoSuchAlgorithmException | java.security.InvalidKeyException exception) {
      throw new IllegalStateException("Não foi possível canonicalizar a reserva", exception);
    }
  }

  private static byte[] canonical(
      String version,
      UUID tenantId,
      String slug,
      UUID serviceId,
      OffsetDateTime startsAt,
      String canonicalName,
      String normalizedPhone)
      throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream command = new DataOutputStream(bytes);
    write(command, version);
    write(command, tenantId.toString());
    write(command, slug);
    write(command, serviceId.toString());
    write(command, startsAt.toInstant().toString());
    write(command, startsAt.getOffset().toString());
    write(command, canonicalName);
    write(command, normalizedPhone);
    command.flush();
    return bytes.toByteArray();
  }

  private static String hmac(byte[] key, byte[] message)
      throws NoSuchAlgorithmException, java.security.InvalidKeyException {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return java.util.HexFormat.of().formatHex(mac.doFinal(message));
  }

  private static void write(DataOutputStream output, String value) throws IOException {
    byte[] content = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(content.length);
    output.write(content);
  }

  private record PublicCommandDigest(
      String persistedValue,
      byte[] legacyCanonical,
      byte[] currentCanonical,
      PublicBookingDigestKeyring keyring)
      implements CommandDigest {

    @Override
    public boolean matches(String storedValue) {
      if (storedValue.matches("[0-9a-f]{64}")) {
        return MessageDigest.isEqual(hexBytes(storedValue), sha256(legacyCanonical));
      }
      String[] segments = storedValue.split(":", 4);
      if (segments.length != 4
          || !segments[0].equals("hmac-sha256")
          || !segments[1].equals("v2")
          || !segments[3].matches("[0-9a-f]{64}")) {
        throw new IllegalStateException("Versão de digest público persistido não suportada");
      }
      byte[] key =
          keyring
              .key(segments[2])
              .orElseThrow(() -> new PublicBookingDigestKeyUnavailableException(segments[2]));
      try {
        return MessageDigest.isEqual(hexBytes(segments[3]), hexBytes(hmac(key, currentCanonical)));
      } catch (NoSuchAlgorithmException | java.security.InvalidKeyException exception) {
        throw new IllegalStateException("Não foi possível validar digest HMAC público", exception);
      }
    }

    private static byte[] sha256(byte[] message) {
      try {
        return MessageDigest.getInstance("SHA-256").digest(message);
      } catch (NoSuchAlgorithmException exception) {
        throw new IllegalStateException("SHA-256 não disponível", exception);
      }
    }

    private static byte[] hexBytes(String value) {
      return java.util.HexFormat.of().parseHex(value);
    }
  }
}
