package com.appagendamento.outbox.api;

public enum DeliveryResult {
  DELIVERED,
  RETRYABLE_FAILURE,
  TERMINAL_FAILURE,
  OUTCOME_UNKNOWN
}
