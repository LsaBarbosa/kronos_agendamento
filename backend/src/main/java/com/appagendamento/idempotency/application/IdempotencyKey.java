package com.appagendamento.idempotency.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class IdempotencyKey {

  private IdempotencyKey() {}

  public static String digest(String rawKey) {
    if (rawKey == null || rawKey.isBlank() || rawKey.length() > 200) {
      throw new IllegalArgumentException("Idempotency-Key inválida");
    }
    return sha256(rawKey.getBytes(StandardCharsets.UTF_8));
  }

  public static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 indisponível", exception);
    }
  }
}
