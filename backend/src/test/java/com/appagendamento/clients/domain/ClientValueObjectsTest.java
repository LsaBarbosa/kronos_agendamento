package com.appagendamento.clients.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ClientValueObjectsTest {

  @Test
  void normalizesOnlyAllowedPhoneSeparatorsAndRequiresExplicitCountryCode() {
    assertThat(E164Phone.fromRaw(" +1 (234)-567.8901 ").value()).isEqualTo("+12345678901");
    assertThat(E164Phone.fromRaw("+5511999999999").value()).isEqualTo("+5511999999999");

    assertThatThrownBy(() -> E164Phone.fromRaw("5511999999999"))
        .isInstanceOf(InvalidE164PhoneException.class);
    assertThatThrownBy(() -> E164Phone.fromRaw("+01234567"))
        .isInstanceOf(InvalidE164PhoneException.class);
    assertThatThrownBy(() -> E164Phone.fromRaw("+1234567"))
        .isInstanceOf(InvalidE164PhoneException.class);
    assertThatThrownBy(() -> E164Phone.fromRaw("+1234567890123456"))
        .isInstanceOf(InvalidE164PhoneException.class);
    assertThatThrownBy(() -> E164Phone.fromRaw("+12/34567"))
        .isInstanceOf(InvalidE164PhoneException.class);
    assertThatThrownBy(() -> E164Phone.fromRaw(null)).isInstanceOf(InvalidE164PhoneException.class);
    assertThatThrownBy(() -> E164Phone.fromNormalized("+1 2345678"))
        .isInstanceOf(InvalidE164PhoneException.class);
  }

  @Test
  void trimsAndCanonicalizesNamesAndEnforcesNonEmptyReasonableLength() {
    assertThat(ClientName.of("  Jose\u0301  ").value()).isEqualTo("José");
    assertThat(ClientName.of("  Ana Silva  ").value()).isEqualTo("Ana Silva");

    assertThatThrownBy(() -> ClientName.of(" \t ")).isInstanceOf(InvalidClientNameException.class);
    assertThatThrownBy(() -> ClientName.of(null)).isInstanceOf(InvalidClientNameException.class);
    assertThatThrownBy(() -> ClientName.of("a".repeat(ClientName.MAX_CODE_POINTS + 1)))
        .isInstanceOf(InvalidClientNameException.class);
    assertThat(ClientName.of("😀".repeat(ClientName.MAX_CODE_POINTS))).isNotNull();
  }
}
