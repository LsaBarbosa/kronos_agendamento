package com.appagendamento.clients.domain;

import java.util.regex.Pattern;

public record E164Phone(String value) {

  private static final Pattern E164_PATTERN = Pattern.compile("\\+[1-9][0-9]{7,14}");
  private static final Pattern ALLOWED_RAW_CHARACTERS = Pattern.compile("[+0-9 .()\\-]+");

  public E164Phone {
    if (value == null || !E164_PATTERN.matcher(value).matches()) {
      throw new InvalidE164PhoneException();
    }
  }

  public static E164Phone fromRaw(String rawPhone) {
    if (rawPhone == null || !ALLOWED_RAW_CHARACTERS.matcher(rawPhone).matches()) {
      throw new InvalidE164PhoneException();
    }
    String normalized = rawPhone.replaceAll("[ .()\\-]", "");
    return new E164Phone(normalized);
  }

  public static E164Phone fromNormalized(String normalizedPhone) {
    return new E164Phone(normalizedPhone);
  }
}
