package com.appagendamento.tenant.application;

import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.audit.api.AuditEvent;
import com.appagendamento.audit.api.AuditEventRecorder;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.tenant.domain.Tenant;
import com.appagendamento.tenant.domain.TenantNotFoundException;
import com.appagendamento.tenant.domain.TenantVersionConflictException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RenameTenant {

  private final TenantRepository tenants;
  private final AuditEventRecorder audit;
  private final AuditCorrelationId correlationId;

  public RenameTenant(
      TenantRepository tenants, AuditEventRecorder audit, AuditCorrelationId correlationId) {
    this.tenants = tenants;
    this.audit = audit;
    this.correlationId = correlationId;
  }

  @Transactional
  public Tenant execute(
      AuthenticatedAdmin actor, UUID requestedTenantId, String newName, long expectedVersion) {
    Tenant current =
        tenants
            .findByTenantIdAndId(actor.tenantId(), requestedTenantId)
            .orElseThrow(TenantNotFoundException::new);
    if (current.version() != expectedVersion) {
      throw new TenantVersionConflictException();
    }
    Tenant renamed = current.rename(newName);
    if (!tenants.rename(actor.tenantId(), requestedTenantId, renamed.name(), expectedVersion)) {
      throw new TenantVersionConflictException();
    }
    Tenant updated =
        tenants
            .findByTenantIdAndId(actor.tenantId(), requestedTenantId)
            .orElseThrow(TenantNotFoundException::new);
    UUID traceId = correlationId.currentTraceId();
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            actor.tenantId(),
            actor.adminUserId(),
            "ADMIN",
            "TENANT_ADMIN",
            "TENANT_RENAMED",
            "TENANT",
            updated.tenantId(),
            "COMMITTED",
            traceId,
            traceId,
            "{\"changed_fields\":[\"name\"],\"version_before\":"
                + current.version()
                + ",\"version_after\":"
                + updated.version()
                + "}"));
    return updated;
  }
}
