package com.appagendamento.appointments.application;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicBookingRecoveryState(String state, PublicAppointmentReceipt receipt) {

  public static PublicBookingRecoveryState preparedUnknown() {
    return new PublicBookingRecoveryState("PREPARED_UNKNOWN", null);
  }

  public static PublicBookingRecoveryState completed(PublicAppointmentReceipt receipt) {
    return new PublicBookingRecoveryState("COMPLETED", receipt);
  }
}
