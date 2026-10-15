package com.appagendamento.audit.api;

import java.util.List;
import java.util.UUID;

public interface AuditEventQuery {

  List<AuditEventView> findForTenant(UUID authenticatedTenantId);
}
