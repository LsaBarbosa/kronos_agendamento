package com.appagendamento.exports.application;

import com.appagendamento.exports.domain.ExportFormat;

public record ExportContent(byte[] bytes, ExportFormat format, String digest) {
  public ExportContent {
    bytes = bytes.clone();
  }

  @Override
  public byte[] bytes() {
    return bytes.clone();
  }
}
