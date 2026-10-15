package com.appagendamento.availability;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.appagendamento.availability.domain.AvailabilityPolicy;
import com.appagendamento.availability.domain.ScheduleValidationException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AvailabilityPolicyTest {

  @Test
  void acceptsDocumentedPolicyChoices() {
    new AvailabilityPolicy(UUID.randomUUID(), 15, 1, 7, 2, 2, 0);
    new AvailabilityPolicy(UUID.randomUUID(), 30, 24, 30, 24, 6, 1);
    new AvailabilityPolicy(UUID.randomUUID(), 60, 12, 90, 8, 24, 2);
  }

  @Test
  void rejectsValuesOutsideDocumentedChoices() {
    assertThatThrownBy(() -> new AvailabilityPolicy(UUID.randomUUID(), 20, 1, 7, 2, 2, 0))
        .isInstanceOf(ScheduleValidationException.class);
    assertThatThrownBy(() -> new AvailabilityPolicy(UUID.randomUUID(), 15, 0, 7, 2, 2, 0))
        .isInstanceOf(ScheduleValidationException.class);
    assertThatThrownBy(() -> new AvailabilityPolicy(UUID.randomUUID(), 15, 1, 14, 2, 2, 0))
        .isInstanceOf(ScheduleValidationException.class);
    assertThatThrownBy(() -> new AvailabilityPolicy(UUID.randomUUID(), 15, 1, 7, 1, 2, 0))
        .isInstanceOf(ScheduleValidationException.class);
    assertThatThrownBy(() -> new AvailabilityPolicy(UUID.randomUUID(), 15, 1, 7, 2, 3, 0))
        .isInstanceOf(ScheduleValidationException.class);
  }
}
