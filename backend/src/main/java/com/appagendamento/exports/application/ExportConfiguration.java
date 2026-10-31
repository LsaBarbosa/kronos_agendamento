package com.appagendamento.exports.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ExportConfiguration {

  private final boolean enabled;
  private final int maximumRows;
  private final int maximumSnapshotBytes;
  private final long leaseSeconds;

  public ExportConfiguration(
      @Value("${app.exports.enabled:false}") boolean enabled,
      @Value("${app.exports.maximum-rows:0}") int maximumRows,
      @Value("${app.exports.maximum-snapshot-bytes:0}") int maximumSnapshotBytes,
      @Value("${app.exports.lease-seconds:60}") long leaseSeconds) {
    if (maximumRows < 0 || maximumSnapshotBytes < 0 || leaseSeconds < 1) {
      throw new IllegalArgumentException("Configuração de exportação inválida");
    }
    this.enabled = enabled;
    this.maximumRows = maximumRows;
    this.maximumSnapshotBytes = maximumSnapshotBytes;
    this.leaseSeconds = leaseSeconds;
  }

  public boolean enabled() {
    return enabled && maximumRows > 0 && maximumSnapshotBytes > 0;
  }

  public int maximumRows() {
    return maximumRows;
  }

  public int maximumSnapshotBytes() {
    return maximumSnapshotBytes;
  }

  public long leaseSeconds() {
    return leaseSeconds;
  }
}
