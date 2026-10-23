package com.appagendamento.exports.application;

public final class ExportStorageException extends RuntimeException {

  private final boolean retryable;

  public ExportStorageException(boolean retryable) {
    this.retryable = retryable;
  }

  public boolean retryable() {
    return retryable;
  }
}
