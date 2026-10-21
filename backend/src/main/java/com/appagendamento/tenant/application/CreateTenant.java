package com.appagendamento.tenant.application;

import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.audit.api.AuditEvent;
import com.appagendamento.audit.api.AuditEventRecorder;
import com.appagendamento.tenant.api.TenantTrialProvisioner;
import com.appagendamento.tenant.domain.Tenant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CreateTenant {

  private final TenantRepository tenants;
  private final AuditEventRecorder audit;
  private final AuditCorrelationId correlationId;
  private final TenantTrialProvisioner initialTrial;

  public CreateTenant(
      TenantRepository tenants,
      AuditEventRecorder audit,
      AuditCorrelationId correlationId,
      TenantTrialProvisioner initialTrial) {
    this.tenants = tenants;
    this.audit = audit;
    this.correlationId = correlationId;
    this.initialTrial = initialTrial;
  }

  @Transactional
  public Tenant execute(String name, String slug, String timezone) {
    Tenant created = tenants.insert(Tenant.create(UUID.randomUUID(), name, slug, timezone));
    UUID traceId = correlationId.currentTraceId();
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            created.tenantId(),
            null,
            "SYSTEM",
            "CONTROLLED_TENANT_PROVISIONER",
            "TENANT_CREATED",
            "TENANT",
            created.tenantId(),
            "COMMITTED",
            traceId,
            traceId,
            "{\"changed_fields\":[\"name\",\"slug\",\"timezone\"]}"));
    initialTrial.provisionIfEligible(created.tenantId());
    return created;
  }
}
