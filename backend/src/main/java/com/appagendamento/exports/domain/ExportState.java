package com.appagendamento.exports.domain;

public enum ExportState {
  REQUESTED,
  PROCESSING,
  RETRY_WAIT,
  AVAILABLE,
  FAILED_FINAL,
  EXPIRED
}
