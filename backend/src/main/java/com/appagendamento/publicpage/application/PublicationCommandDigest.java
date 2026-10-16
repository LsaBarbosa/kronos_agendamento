package com.appagendamento.publicpage.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

public final class PublicationCommandDigest {

  private PublicationCommandDigest() {}

  public static String calculate(
      UUID tenantId, String operation, long expectedVersion, String content) {
    String canonical =
        "publication-command.v1\n"
            + tenantId
            + "\n"
            + operation
            + "\n"
            + expectedVersion
            + "\n"
            + content;
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 indisponível", exception);
    }
  }
}
