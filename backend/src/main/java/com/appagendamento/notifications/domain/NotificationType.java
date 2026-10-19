package com.appagendamento.notifications.domain;

import java.util.Optional;

public enum NotificationType {
  APPOINTMENT_CONFIRMED("AppointmentConfirmed", "CONFIRMED"),
  APPOINTMENT_RESCHEDULED("AppointmentRescheduled", "CONFIRMED"),
  APPOINTMENT_CANCELLED("AppointmentCancelled", null),
  APPOINTMENT_REMINDER("AppointmentReminder", "CONFIRMED");

  private final String eventType;
  private final String requiredStatus;

  NotificationType(String eventType, String requiredStatus) {
    this.eventType = eventType;
    this.requiredStatus = requiredStatus;
  }

  public String eventType() {
    return eventType;
  }

  public boolean matchesCurrent(String status) {
    return this == APPOINTMENT_CANCELLED
        ? status.equals("CANCELLED_BY_CLIENT") || status.equals("CANCELLED_BY_PROVIDER")
        : requiredStatus.equals(status);
  }

  public static Optional<NotificationType> fromEvent(String eventType) {
    return java.util.Arrays.stream(values())
        .filter(type -> type.eventType.equals(eventType))
        .findFirst();
  }
}
