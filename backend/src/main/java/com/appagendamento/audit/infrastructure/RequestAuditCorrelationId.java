package com.appagendamento.audit.infrastructure;

import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.platform.infrastructure.web.CorrelationIdFilter;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class RequestAuditCorrelationId implements AuditCorrelationId {

  @Override
  public UUID currentTraceId() {
    if (RequestContextHolder.getRequestAttributes()
        instanceof ServletRequestAttributes attributes) {
      Object value = attributes.getRequest().getAttribute(CorrelationIdFilter.ATTRIBUTE);
      if (value instanceof String traceId) {
        return UUID.fromString(traceId);
      }
    }
    return UUID.randomUUID();
  }
}
