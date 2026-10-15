package com.appagendamento.audit.api;

import java.util.UUID;

public interface AuditCorrelationId {

  UUID currentTraceId();
}
