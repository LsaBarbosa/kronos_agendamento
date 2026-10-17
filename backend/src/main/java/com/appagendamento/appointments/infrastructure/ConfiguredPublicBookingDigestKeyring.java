package com.appagendamento.appointments.infrastructure;

import com.appagendamento.appointments.api.PublicBookingDigestKeyring;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class ConfiguredPublicBookingDigestKeyring implements PublicBookingDigestKeyring {

  private final String activeKeyId;
  private final Map<String, byte[]> keys;

  public ConfiguredPublicBookingDigestKeyring(
      @Value("${app.public-booking.digest.active-key-id:}") String activeKeyId,
      @Value("${app.public-booking.digest.keys:}") String encodedKeys) {
    if (activeKeyId == null || !activeKeyId.matches("[A-Za-z0-9_-]{1,32}")) {
      throw new IllegalStateException("PUBLIC_BOOKING_HMAC_ACTIVE_KEY_ID inválido ou ausente");
    }
    Map<String, byte[]> parsed = parseKeys(encodedKeys);
    if (!parsed.containsKey(activeKeyId)) {
      throw new IllegalStateException("A chave HMAC ativa não está no keyring configurado");
    }
    this.activeKeyId = activeKeyId;
    this.keys = Map.copyOf(parsed);
  }

  @Override
  public String activeKeyId() {
    return activeKeyId;
  }

  @Override
  public byte[] activeKey() {
    return keys.get(activeKeyId).clone();
  }

  @Override
  public Optional<byte[]> key(String keyId) {
    return Optional.ofNullable(keys.get(keyId)).map(byte[]::clone);
  }

  private static Map<String, byte[]> parseKeys(String encodedKeys) {
    if (encodedKeys == null || encodedKeys.isBlank()) {
      throw new IllegalStateException("PUBLIC_BOOKING_HMAC_KEYS é obrigatório");
    }
    Map<String, byte[]> parsed = new LinkedHashMap<>();
    for (String entry : encodedKeys.split(";", -1)) {
      String[] pair = entry.split("=", 2);
      if (pair.length != 2 || !pair[0].matches("[A-Za-z0-9_-]{1,32}")) {
        throw new IllegalStateException("Entrada inválida no keyring HMAC público");
      }
      byte[] key;
      try {
        key = Base64.getDecoder().decode(pair[1]);
      } catch (IllegalArgumentException exception) {
        throw new IllegalStateException("Chave HMAC pública não está em Base64 válido");
      }
      if (key.length < 32 || parsed.putIfAbsent(pair[0], key) != null) {
        throw new IllegalStateException("Keyring HMAC exige IDs únicos e chaves de 256 bits");
      }
    }
    return parsed;
  }
}
