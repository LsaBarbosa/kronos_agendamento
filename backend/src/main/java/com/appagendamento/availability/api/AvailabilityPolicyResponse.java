package com.appagendamento.availability.api;

import com.appagendamento.availability.domain.AvailabilityPolicy;

public record AvailabilityPolicyResponse(
    boolean configured,
    long version,
    Integer slotStepMinutes,
    Integer minimumNoticeHours,
    Integer bookingHorizonDays,
    Integer cancellationCutoffHours,
    Integer reminderOffsetHours) {

  public static AvailabilityPolicyResponse absent() {
    return new AvailabilityPolicyResponse(false, 0, null, null, null, null, null);
  }

  public static AvailabilityPolicyResponse from(AvailabilityPolicy policy) {
    return new AvailabilityPolicyResponse(
        true,
        policy.version(),
        policy.slotStepMinutes(),
        policy.minimumNoticeHours(),
        policy.bookingHorizonDays(),
        policy.cancellationCutoffHours(),
        policy.reminderOffsetHours());
  }
}
