package com.appagendamento.clients.domain;

import java.text.Normalizer;

public record ClientName(String value) {

  public static final int MAX_CODE_POINTS = 120;

  public ClientName {
    if (value == null) {
      throw new InvalidClientNameException();
    }
    value = Normalizer.normalize(value.strip(), Normalizer.Form.NFC).strip();
    int codePoints = value.codePointCount(0, value.length());
    if (value.isEmpty() || codePoints > MAX_CODE_POINTS) {
      throw new InvalidClientNameException();
    }
  }

  public static ClientName of(String value) {
    return new ClientName(value);
  }
}
