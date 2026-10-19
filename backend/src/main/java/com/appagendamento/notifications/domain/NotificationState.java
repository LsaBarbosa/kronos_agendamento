package com.appagendamento.notifications.domain;

public enum NotificationState {
  PENDING,
  CLAIMED,
  RETRY,
  ACCEPTED,
  DELIVERED,
  DELIVERY_FAILED,
  FAILED_FINAL,
  OUTCOME_UNKNOWN,
  OBSOLETE
}
