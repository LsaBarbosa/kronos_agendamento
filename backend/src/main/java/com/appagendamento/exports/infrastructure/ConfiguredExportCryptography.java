package com.appagendamento.exports.infrastructure;

import com.appagendamento.exports.application.ExportCryptography;
import com.appagendamento.exports.domain.ExportUnavailableException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ConfiguredExportCryptography implements ExportCryptography {

  private static final int NONCE_SIZE = 12;
  private static final int TAG_BITS = 128;
  private final String activeKeyId;
  private final Map<String, byte[]> keys;
  private final SecureRandom random = new SecureRandom();

  public ConfiguredExportCryptography(
      @Value("${app.exports.encryption.active-key-id:}") String activeKeyId,
      @Value("${app.exports.encryption.keys:}") String keyRing) {
    this.activeKeyId = activeKeyId;
    this.keys = parse(keyRing);
  }

  @Override
  public boolean available() {
    byte[] key = keys.get(activeKeyId);
    return activeKeyId != null && !activeKeyId.isBlank() && key != null && key.length == 32;
  }

  @Override
  public EncryptedData encrypt(byte[] plaintext, String purpose, String tenantId, String exportId) {
    if (!available()) throw new ExportUnavailableException();
    byte[] nonce = new byte[NONCE_SIZE];
    random.nextBytes(nonce);
    try {
      Cipher cipher =
          cipher(Cipher.ENCRYPT_MODE, keys.get(activeKeyId), nonce, purpose, tenantId, exportId);
      byte[] encrypted = cipher.doFinal(plaintext);
      return new EncryptedData(
          activeKeyId,
          ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array());
    } catch (GeneralSecurityException exception) {
      throw new ExportUnavailableException();
    }
  }

  @Override
  public byte[] decrypt(
      byte[] ciphertext, String keyId, String purpose, String tenantId, String exportId) {
    byte[] key = keys.get(keyId);
    if (key == null
        || key.length != 32
        || ciphertext == null
        || ciphertext.length < NONCE_SIZE + 16) {
      throw new ExportUnavailableException();
    }
    byte[] nonce = java.util.Arrays.copyOfRange(ciphertext, 0, NONCE_SIZE);
    byte[] payload = java.util.Arrays.copyOfRange(ciphertext, NONCE_SIZE, ciphertext.length);
    try {
      return cipher(Cipher.DECRYPT_MODE, key, nonce, purpose, tenantId, exportId).doFinal(payload);
    } catch (GeneralSecurityException exception) {
      throw new ExportUnavailableException();
    }
  }

  private static Cipher cipher(
      int mode, byte[] key, byte[] nonce, String purpose, String tenantId, String exportId)
      throws GeneralSecurityException {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
    cipher.updateAAD(
        ("agd-export:v1|" + purpose + "|" + tenantId + "|" + exportId)
            .getBytes(StandardCharsets.UTF_8));
    return cipher;
  }

  private static Map<String, byte[]> parse(String keyRing) {
    Map<String, byte[]> result = new HashMap<>();
    if (keyRing == null || keyRing.isBlank()) return Map.of();
    for (String entry : keyRing.split(";")) {
      String[] parts = entry.split("=", 2);
      if (parts.length != 2
          || !parts[0].matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
          || result.containsKey(parts[0])) {
        return Map.of();
      }
      try {
        byte[] decoded = Base64.getDecoder().decode(parts[1]);
        if (decoded.length != 32) return Map.of();
        result.put(parts[0], decoded);
      } catch (IllegalArgumentException exception) {
        return Map.of();
      }
    }
    return Map.copyOf(result);
  }
}
