package com.appagendamento.audit.api;

public interface AuditEventRecorder {

  void record(AuditEvent event);
}
