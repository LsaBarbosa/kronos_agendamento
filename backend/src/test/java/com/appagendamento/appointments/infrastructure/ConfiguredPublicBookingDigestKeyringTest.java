package com.appagendamento.appointments.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ConfiguredPublicBookingDigestKeyringTest {

  private static final String KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

  @Test
  void acceptsActiveKeyAndReturnsDefensiveCopies() {
    var keyring = new ConfiguredPublicBookingDigestKeyring("active_1", "active_1=" + KEY);
    byte[] first = keyring.activeKey();
    first[0] = 1;

    assertThat(keyring.activeKey()[0]).isEqualTo((byte) 0);
    assertThat(keyring.activeKeyId()).isEqualTo("active_1");
    assertThat(keyring.key("active_1").orElseThrow()[0]).isEqualTo((byte) 0);
  }

  @Test
  void rejectsMissingActiveKeyAndMalformedOrWeakKeyrings() {
    assertThatThrownBy(() -> new ConfiguredPublicBookingDigestKeyring("", "active_1=" + KEY))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new ConfiguredPublicBookingDigestKeyring("missing", "active_1=" + KEY))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new ConfiguredPublicBookingDigestKeyring("active_1", ""))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new ConfiguredPublicBookingDigestKeyring("active_1", "active_1=short"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                new ConfiguredPublicBookingDigestKeyring(
                    "active_1", "active_1=" + KEY + ";active_1=" + KEY))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new ConfiguredPublicBookingDigestKeyring("active_1", "invalid"))
        .isInstanceOf(IllegalStateException.class);
  }
}
