package com.appagendamento.tenant.application;

import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.audit.api.AuditEvent;
import com.appagendamento.audit.api.AuditEventRecorder;
import com.appagendamento.idempotency.api.CommandResultCodec;
import com.appagendamento.idempotency.api.IdempotencyCoordinator;
import com.appagendamento.idempotency.api.IdempotentCommandResult;
import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.outbox.api.OutboxEvent;
import com.appagendamento.outbox.api.OutboxEventRecorder;
import com.appagendamento.tenant.domain.Tenant;
import com.appagendamento.tenant.domain.TenantNotFoundException;
import com.appagendamento.tenant.domain.TenantVersionConflictException;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RenameTenant {

  private final TenantRepository tenants;
  private final AuditEventRecorder audit;
  private final AuditCorrelationId correlationId;
  private final AdminAccessPolicy accessPolicy;
  private final IdempotencyCoordinator idempotency;
  private final OutboxEventRecorder outbox;
  private final CommandResultCodec<Tenant> resultCodec;

  public RenameTenant(
      TenantRepository tenants,
      AuditEventRecorder audit,
      AuditCorrelationId correlationId,
      AdminAccessPolicy accessPolicy,
      IdempotencyCoordinator idempotency,
      OutboxEventRecorder outbox,
      CommandResultCodec<Tenant> resultCodec) {
    this.tenants = tenants;
    this.audit = audit;
    this.correlationId = correlationId;
    this.accessPolicy = accessPolicy;
    this.idempotency = idempotency;
    this.outbox = outbox;
    this.resultCodec = resultCodec;
  }

  public IdempotentCommandResult<Tenant> execute(
      AuthenticatedAdmin actor,
      UUID requestedTenantId,
      String idempotencyKey,
      String newName,
      long expectedVersion) {
    String normalizedName = newName == null ? "" : newName.strip();
    String requestDigest =
        RenameTenantCommandDigest.calculate(
            actor.tenantId(), requestedTenantId, normalizedName, expectedVersion);
    return idempotency.execute(
        actor.tenantId(),
        "tenant.rename.v1",
        idempotencyKey,
        requestDigest,
        () -> revalidate(actor, requestedTenantId),
        operationId ->
            rename(actor, requestedTenantId, normalizedName, expectedVersion, operationId),
        resultCodec);
  }

  private void revalidate(AuthenticatedAdmin actor, UUID requestedTenantId) {
    if (!accessPolicy.isCurrent(actor)
        || tenants.findByTenantIdAndId(actor.tenantId(), requestedTenantId).isEmpty()) {
      throw new TenantNotFoundException();
    }
  }

  private Tenant rename(
      AuthenticatedAdmin actor,
      UUID requestedTenantId,
      String newName,
      long expectedVersion,
      UUID operationId) {
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
    outbox.record(
        new OutboxEvent(
            UUID.randomUUID(),
            updated.tenantId(),
            "TENANT",
            updated.tenantId(),
            updated.version(),
            "TENANT_RENAMED",
            1,
            operationId,
            traceId,
            "{\"tenantId\":\"" + updated.tenantId() + "\",\"version\":" + updated.version() + "}"));
    return updated;
  }
}
