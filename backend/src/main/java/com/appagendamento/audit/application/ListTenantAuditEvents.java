package com.appagendamento.audit.application;

import com.appagendamento.audit.api.AuditEventQuery;
import com.appagendamento.audit.api.AuditEventView;
import com.appagendamento.identityaccess.api.AuthenticatedTenant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ListTenantAuditEvents {

  private final AuditEventQuery events;

  public ListTenantAuditEvents(AuditEventQuery events) {
    this.events = events;
  }

  public List<AuditEventView> execute(AuthenticatedTenant principal) {
    return events.findForTenant(principal.tenantId());
  }
}
