package com.appagendamento.exports.domain;

public final class ExportConflictException extends RuntimeException {

  private final String code;

  public ExportConflictException(String code) {
    this.code = code;
  }

  public String code() {
    return code;
  }
}
