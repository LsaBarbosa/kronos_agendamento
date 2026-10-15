package com.appagendamento.catalog.application;

import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.audit.api.AuditEventRecorder;
import com.appagendamento.catalog.domain.Service;
import com.appagendamento.catalog.domain.ServiceNotFoundException;
import com.appagendamento.catalog.domain.ServiceStatus;
import com.appagendamento.catalog.domain.ServiceVersionConflictException;
import com.appagendamento.idempotency.api.CommandResultCodec;
import com.appagendamento.idempotency.api.IdempotencyCoordinator;
import com.appagendamento.idempotency.api.IdempotentCommandResult;
import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import java.time.Instant;
import java.util.UUID;

@org.springframework.stereotype.Service
public class SetServiceStatus {

  private final ServiceRepository services;
  private final AdminAccessPolicy accessPolicy;
  private final IdempotencyCoordinator idempotency;
  private final AuditEventRecorder audit;
  private final AuditCorrelationId correlationId;
  private final CommandResultCodec<Service> resultCodec;

  public SetServiceStatus(
      ServiceRepository services,
      AdminAccessPolicy accessPolicy,
      IdempotencyCoordinator idempotency,
      AuditEventRecorder audit,
      AuditCorrelationId correlationId,
      CommandResultCodec<Service> resultCodec) {
    this.services = services;
    this.accessPolicy = accessPolicy;
    this.idempotency = idempotency;
    this.audit = audit;
    this.correlationId = correlationId;
    this.resultCodec = resultCodec;
  }

  public IdempotentCommandResult<Service> execute(
      AuthenticatedAdmin actor,
      UUID serviceId,
      String idempotencyKey,
      long expectedVersion,
      ServiceStatus targetStatus) {
    String digest =
        ServiceCommandDigest.activation(actor.tenantId(), serviceId, expectedVersion, targetStatus);
    return idempotency.execute(
        actor.tenantId(),
        "catalog.service." + targetStatus.name().toLowerCase() + ".v1",
        idempotencyKey,
        digest,
        () -> revalidate(actor, serviceId),
        operationId -> change(actor, serviceId, expectedVersion, targetStatus),
        resultCodec);
  }

  private void revalidate(AuthenticatedAdmin actor, UUID serviceId) {
    if (!accessPolicy.isCurrent(actor)) {
      throw new CatalogAccessDeniedException();
    }
    if (services.findByTenantAndId(actor.tenantId(), serviceId).isEmpty()) {
      throw new ServiceNotFoundException();
    }
  }

  private Service change(
      AuthenticatedAdmin actor, UUID serviceId, long expectedVersion, ServiceStatus targetStatus) {
    Service current =
        services
            .findByTenantAndId(actor.tenantId(), serviceId)
            .orElseThrow(ServiceNotFoundException::new);
    if (current.version() != expectedVersion) {
      throw new ServiceVersionConflictException();
    }
    Service updated = current.withStatus(targetStatus, Instant.now());
    if (updated == current) {
      return current;
    }
    if (!services.update(updated, expectedVersion)) {
      throw new ServiceVersionConflictException();
    }
    Service persisted =
        services
            .findByTenantAndId(actor.tenantId(), serviceId)
            .orElseThrow(ServiceNotFoundException::new);
    String action =
        targetStatus == ServiceStatus.ACTIVE ? "SERVICE_ACTIVATED" : "SERVICE_DEACTIVATED";
    audit.record(
        CreateService.event(
            actor,
            persisted,
            action,
            "{\"changed_fields\":[\"status\"]}",
            correlationId.currentTraceId()));
    return persisted;
  }
}
