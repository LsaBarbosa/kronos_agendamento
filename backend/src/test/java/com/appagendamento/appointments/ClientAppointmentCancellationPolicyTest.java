package com.appagendamento.appointments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.appagendamento.appointments.domain.ClientAppointmentCancellationPolicy;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ClientAppointmentCancellationPolicyTest {

  private final ClientAppointmentCancellationPolicy policy =
      new ClientAppointmentCancellationPolicy();

  @Test
  void allowsCancellationImmediatelyBeforeAndAtCutoffButRejectsAfter() {
    Instant startsAt = Instant.parse("2026-10-20T15:00:00Z");
    Instant cutoff = startsAt.minusSeconds(4 * 60 * 60L);

    assertThat(policy.allows(startsAt, cutoff.minusNanos(1), 4)).isTrue();
    assertThat(policy.allows(startsAt, cutoff, 4)).isTrue();
    assertThat(policy.allows(startsAt, cutoff.plusNanos(1), 4)).isFalse();
  }

  @Test
  void rejectsInvalidCutoffAndMissingInstants() {
    Instant startsAt = Instant.parse("2026-10-20T15:00:00Z");

    assertThatThrownBy(() -> policy.allows(startsAt, Instant.EPOCH, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> policy.allows(startsAt, Instant.EPOCH, 25))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> policy.allows(null, Instant.EPOCH, 4))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> policy.allows(startsAt, null, 4))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
