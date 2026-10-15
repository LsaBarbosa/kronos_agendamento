package com.appagendamento.tenant.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

public record TenantSlug(String value) {

  private static final Pattern VALID_SLUG = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");

  public TenantSlug {
    value =
        Normalizer.normalize(value == null ? "" : value.strip(), Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT);
    if (value.length() > 63 || !VALID_SLUG.matcher(value).matches()) {
      throw new IllegalArgumentException("Slug inválido");
    }
  }
}
