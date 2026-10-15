package com.appagendamento.audit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.appagendamento.audit.api.AuditEvent;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditEventTest {

  @Test
  void rejectsUnapprovedDetailsInsteadOfSerializingSensitiveValues() {
    for (String details :
        new String[] {
          "{\"phone\":\"+5511999999999\"}",
          "{\"access_code\":\"123456\"}",
          "{\"access_token\":\"secret\"}",
          "{\"cookie\":\"AGDSESSION=secret\"}",
          "{\"credential\":\"secret\"}",
          "{\"password\":\"secret\"}"
        }) {
      assertThatThrownBy(() -> event(details)).isInstanceOf(IllegalArgumentException.class);
    }
  }

  private static AuditEvent event(String details) {
    return new AuditEvent(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "ADMIN",
        "TENANT_ADMIN",
        "TENANT_RENAMED",
        "TENANT",
        UUID.randomUUID(),
        "COMMITTED",
        UUID.randomUUID(),
        UUID.randomUUID(),
        details);
  }
}
