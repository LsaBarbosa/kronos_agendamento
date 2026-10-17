package com.appagendamento.appointments.domain;

public enum AppointmentOutcome {
  CANCEL_BY_PROVIDER(
      AppointmentStatus.CANCELLED_BY_PROVIDER,
      "APPOINTMENT_CANCELLED_BY_PROVIDER",
      "APPOINTMENT_CANCELLED_BY_PROVIDER",
      "AppointmentCancelled"),
  COMPLETE(
      AppointmentStatus.COMPLETED,
      "APPOINTMENT_COMPLETED",
      "APPOINTMENT_COMPLETED",
      "AppointmentCompleted"),
  MARK_NO_SHOW(
      AppointmentStatus.NO_SHOW,
      "APPOINTMENT_NO_SHOW",
      "APPOINTMENT_NO_SHOW_RECORDED",
      "ClientNoShowRecorded");

  private final AppointmentStatus status;
  private final String historyReason;
  private final String auditAction;
  private final String eventType;

  AppointmentOutcome(
      AppointmentStatus status, String historyReason, String auditAction, String eventType) {
    this.status = status;
    this.historyReason = historyReason;
    this.auditAction = auditAction;
    this.eventType = eventType;
  }

  public AppointmentStatus status() {
    return status;
  }

  public String historyReason() {
    return historyReason;
  }

  public String auditAction() {
    return auditAction;
  }

  public String eventType() {
    return eventType;
  }
}
