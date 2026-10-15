package com.appagendamento.catalog.application;

import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.audit.api.AuditEvent;
import com.appagendamento.audit.api.AuditEventRecorder;
import com.appagendamento.catalog.domain.Service;
import com.appagendamento.catalog.domain.ServiceStatus;
import com.appagendamento.idempotency.api.CommandResultCodec;
import com.appagendamento.idempotency.api.IdempotencyCoordinator;
import com.appagendamento.idempotency.api.IdempotentCommandResult;
import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.outbox.api.OutboxEvent;
import com.appagendamento.outbox.api.OutboxEventRecorder;
import java.time.Instant;
import java.util.UUID;

@org.springframework.stereotype.Service
public class CreateService {

  private final ServiceRepository services;
  private final AdminAccessPolicy accessPolicy;
  private final IdempotencyCoordinator idempotency;
  private final AuditEventRecorder audit;
  private final AuditCorrelationId correlationId;
  private final OutboxEventRecorder outbox;
  private final CommandResultCodec<Service> resultCodec;

  public CreateService(
      ServiceRepository services,
      AdminAccessPolicy accessPolicy,
      IdempotencyCoordinator idempotency,
      AuditEventRecorder audit,
      AuditCorrelationId correlationId,
      OutboxEventRecorder outbox,
      CommandResultCodec<Service> resultCodec) {
    this.services = services;
    this.accessPolicy = accessPolicy;
    this.idempotency = idempotency;
    this.audit = audit;
    this.correlationId = correlationId;
    this.outbox = outbox;
    this.resultCodec = resultCodec;
  }

  public IdempotentCommandResult<Service> execute(
      AuthenticatedAdmin actor,
      String idempotencyKey,
      String name,
      String description,
      int durationMinutes,
      int bufferAfterMinutes,
      ServiceStatus status) {
    Service candidate =
        Service.create(
            UUID.randomUUID(),
            actor.tenantId(),
            name,
            description,
            durationMinutes,
            bufferAfterMinutes,
            status,
            Instant.now());
    String digest =
        ServiceCommandDigest.create(
            actor.tenantId(),
            candidate.name(),
            candidate.description(),
            candidate.durationMinutes(),
            candidate.bufferAfterMinutes(),
            candidate.status());
    return idempotency.execute(
        actor.tenantId(),
        "catalog.service.create.v1",
        idempotencyKey,
        digest,
        () -> requireCurrent(actor),
        operationId -> create(actor, candidate, operationId),
        resultCodec);
  }

  private void requireCurrent(AuthenticatedAdmin actor) {
    if (!accessPolicy.isCurrent(actor)) {
      throw new CatalogAccessDeniedException();
    }
  }

  private Service create(AuthenticatedAdmin actor, Service candidate, UUID operationId) {
    Service created = services.insert(candidate);
    UUID traceId = correlationId.currentTraceId();
    audit.record(
        event(
            actor,
            created,
            "SERVICE_CREATED",
            "{\"changed_fields\":[\"name\",\"description\",\"duration_minutes\",\"buffer_after_minutes\",\"status\"]}",
            traceId));
    outbox.record(
        new OutboxEvent(
            UUID.randomUUID(),
            created.tenantId(),
            "SERVICE",
            created.serviceId(),
            created.version(),
            "ServiceCreated",
            1,
            operationId,
            traceId,
            "{\"serviceId\":\""
                + created.serviceId()
                + "\",\"version\":"
                + created.version()
                + "}"));
    return created;
  }

  static AuditEvent event(
      AuthenticatedAdmin actor, Service service, String action, String details, UUID traceId) {
    return new AuditEvent(
        UUID.randomUUID(),
        actor.tenantId(),
        actor.adminUserId(),
        "ADMIN",
        "TENANT_ADMIN",
        action,
        "SERVICE",
        service.serviceId(),
        "COMMITTED",
        traceId,
        traceId,
        details);
  }
}
