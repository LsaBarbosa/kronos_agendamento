package com.appagendamento.appointments.application;

public record AppointmentCreationContext(
    String actorType, String actorReference, String reasonCode, String reason) {

  public AppointmentCreationContext {
    if (actorType == null
        || actorReference == null
        || reasonCode == null
        || !(actorType.equals("PUBLIC")
                && actorReference.equals("PUBLIC_BOOKING")
                && reasonCode.equals("APPOINTMENT_CREATED")
                && reason == null
            || actorType.equals("ADMIN")
                && actorReference.equals("TENANT_ADMIN")
                && (reasonCode.equals("ADMIN_BOOKING") && reason == null
                    || reasonCode.equals("ADMIN_BOOKING_OVERRIDE")
                        && reason != null
                        && !reason.isBlank()
                        && reason.length() <= 500))) {
      throw new IllegalArgumentException("Origem da criação da reserva inválida");
    }
  }

  public static AppointmentCreationContext publicBooking() {
    return new AppointmentCreationContext("PUBLIC", "PUBLIC_BOOKING", "APPOINTMENT_CREATED", null);
  }

  public static AppointmentCreationContext administrative(String reason) {
    return new AppointmentCreationContext(
        "ADMIN",
        "TENANT_ADMIN",
        reason == null ? "ADMIN_BOOKING" : "ADMIN_BOOKING_OVERRIDE",
        reason);
  }
}
