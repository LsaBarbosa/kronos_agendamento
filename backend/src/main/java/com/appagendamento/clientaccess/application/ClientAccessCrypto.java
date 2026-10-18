package com.appagendamento.clientaccess.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ClientAccessCrypto {

  private final String verifierKeyId;
  private final String phoneEncryptionKeyId;
  private final String fingerprintKeyId;
  private final Map<String, byte[]> verifierKeys;
  private final byte[] fingerprintKey;
  private final Map<String, byte[]> encryptionKeys;
  private final SecureRandom random = new SecureRandom();

  public ClientAccessCrypto(
      @Value("${app.client-access.keys.verifier-active-id:}") String verifierKeyId,
      @Value("${app.client-access.keys.phone-encryption-active-id:}") String phoneEncryptionKeyId,
      @Value("${app.client-access.keys.fingerprint-id:}") String fingerprintKeyId,
      @Value("${app.client-access.keys.verifier-ring:}") String verifierKeys,
      @Value("${app.client-access.keys.fingerprint:}") String fingerprintKey,
      @Value("${app.client-access.keys.phone-encryption-ring:}") String encryptionKeys) {
    this.verifierKeyId = verifierKeyId;
    this.phoneEncryptionKeyId = phoneEncryptionKeyId;
    this.fingerprintKeyId = fingerprintKeyId;
    this.verifierKeys = parseRing(verifierKeys);
    this.fingerprintKey = decode(fingerprintKey);
    this.encryptionKeys = parseRing(encryptionKeys);
  }

  public boolean configured() {
    return verifierKeyId.matches("[A-Za-z0-9._-]{1,64}")
        && verifierKeys.containsKey(verifierKeyId)
        && phoneEncryptionKeyId.matches("[A-Za-z0-9._-]{1,64}")
        && encryptionKeys.containsKey(phoneEncryptionKeyId)
        && fingerprintKeyId.matches("[A-Za-z0-9._-]{1,64}")
        && fingerprintKey != null;
  }

  public String keyId() {
    return verifierKeyId;
  }

  public String phoneKeyId() {
    return phoneEncryptionKeyId;
  }

  public String fingerprintKeyId() {
    return fingerprintKeyId;
  }

  public byte[] newSalt() {
    byte[] salt = new byte[16];
    random.nextBytes(salt);
    return salt;
  }

  public String newCode() {
    return "%06d".formatted(random.nextInt(1_000_000));
  }

  public String newSessionToken() {
    byte[] token = new byte[32];
    random.nextBytes(token);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
  }

  public byte[] fingerprint(String phone) {
    return hmac(fingerprintKey, phone.getBytes(StandardCharsets.UTF_8));
  }

  public byte[] verifier(String code, byte[] salt, String challengeId, String tenantId) {
    return verifier(code, salt, challengeId, tenantId, verifierKeyId);
  }

  public byte[] verifier(
      String code, byte[] salt, String challengeId, String tenantId, String version) {
    byte[] context = (tenantId + ":" + challengeId + ":" + code).getBytes(StandardCharsets.UTF_8);
    byte[] input = new byte[salt.length + context.length];
    System.arraycopy(salt, 0, input, 0, salt.length);
    System.arraycopy(context, 0, input, salt.length, context.length);
    byte[] key = verifierKeys.get(version);
    return key == null ? null : hmac(key, input);
  }

  public byte[] digest(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  public boolean matches(byte[] expected, byte[] actual) {
    return MessageDigest.isEqual(expected, actual);
  }

  public byte[] encryptPhone(String phone) {
    return encryptPhone(phone, phoneEncryptionKeyId);
  }

  public byte[] encryptPhone(String phone, String version) {
    try {
      byte[] nonce = new byte[12];
      random.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      byte[] key = encryptionKeys.get(version);
      if (key == null) throw new IllegalStateException("Versão de chave indisponível");
      cipher.init(
          Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
      byte[] encrypted = cipher.doFinal(phone.getBytes(StandardCharsets.UTF_8));
      byte[] result = Arrays.copyOf(nonce, nonce.length + encrypted.length);
      System.arraycopy(encrypted, 0, result, nonce.length, encrypted.length);
      return result;
    } catch (Exception exception) {
      throw new IllegalStateException("Falha ao proteger telefone", exception);
    }
  }

  public String decryptPhone(byte[] ciphertext, String version) {
    try {
      byte[] nonce = Arrays.copyOfRange(ciphertext, 0, 12);
      byte[] encrypted = Arrays.copyOfRange(ciphertext, 12, ciphertext.length);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      byte[] key = encryptionKeys.get(version);
      if (key == null) throw new IllegalStateException("Versão de chave indisponível");
      cipher.init(
          Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
      return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    } catch (Exception exception) {
      throw new IllegalStateException("Falha ao proteger telefone", exception);
    }
  }

  public byte[] sessionDigest(String token) {
    return digest(token);
  }

  private static byte[] decode(String value) {
    try {
      byte[] decoded = Base64.getDecoder().decode(value);
      return decoded.length == 32 ? decoded : null;
    } catch (RuntimeException exception) {
      return null;
    }
  }

  private static Map<String, byte[]> parseRing(String value) {
    Map<String, byte[]> result = new HashMap<>();
    if (value == null || value.isBlank()) return Map.of();
    for (String entry : value.split(",")) {
      String[] pair = entry.split("=", 2);
      if (pair.length != 2 || !pair[0].matches("[A-Za-z0-9._-]{1,64}")) return Map.of();
      byte[] key = decode(pair[1]);
      if (key == null || result.putIfAbsent(pair[0], key) != null) return Map.of();
    }
    return Map.copyOf(result);
  }

  private static byte[] hmac(byte[] key, byte[] input) {
    if (key == null) {
      throw new IllegalStateException("Segredos de acesso do cliente não configurados");
    }
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(input);
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }
}
