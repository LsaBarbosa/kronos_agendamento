package com.appagendamento.catalog.application;

import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.audit.api.AuditEventRecorder;
import com.appagendamento.catalog.domain.Service;
import com.appagendamento.catalog.domain.ServiceNotFoundException;
import com.appagendamento.catalog.domain.ServiceVersionConflictException;
import com.appagendamento.idempotency.api.CommandResultCodec;
import com.appagendamento.idempotency.api.IdempotencyCoordinator;
import com.appagendamento.idempotency.api.IdempotentCommandResult;
import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@org.springframework.stereotype.Service
public class UpdateService {

  private final ServiceRepository services;
  private final AdminAccessPolicy accessPolicy;
  private final IdempotencyCoordinator idempotency;
  private final AuditEventRecorder audit;
  private final AuditCorrelationId correlationId;
  private final CommandResultCodec<Service> resultCodec;

  public UpdateService(
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
      String name,
      String description,
      Integer durationMinutes,
      Integer bufferAfterMinutes) {
    if (name == null
        && description == null
        && durationMinutes == null
        && bufferAfterMinutes == null) {
      throw new IllegalArgumentException("Informe ao menos um campo para atualização");
    }
    String digest =
        ServiceCommandDigest.update(
            actor.tenantId(),
            serviceId,
            expectedVersion,
            name,
            description,
            durationMinutes,
            bufferAfterMinutes);
    return idempotency.execute(
        actor.tenantId(),
        "catalog.service.update.v1",
        idempotencyKey,
        digest,
        () -> revalidate(actor, serviceId),
        operationId ->
            update(
                actor,
                serviceId,
                expectedVersion,
                name,
                description,
                durationMinutes,
                bufferAfterMinutes),
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

  private Service update(
      AuthenticatedAdmin actor,
      UUID serviceId,
      long expectedVersion,
      String name,
      String description,
      Integer durationMinutes,
      Integer bufferAfterMinutes) {
    Service current =
        services
            .findByTenantAndId(actor.tenantId(), serviceId)
            .orElseThrow(ServiceNotFoundException::new);
    if (current.version() != expectedVersion) {
      throw new ServiceVersionConflictException();
    }
    Service updated =
        current.update(name, description, durationMinutes, bufferAfterMinutes, Instant.now());
    List<String> changedFields = changedFields(current, updated);
    if (changedFields.isEmpty()) {
      return current;
    }
    if (!services.update(updated, expectedVersion)) {
      throw new ServiceVersionConflictException();
    }
    Service persisted =
        services
            .findByTenantAndId(actor.tenantId(), serviceId)
            .orElseThrow(ServiceNotFoundException::new);
    audit.record(
        CreateService.event(
            actor,
            persisted,
            "SERVICE_UPDATED",
            updateDetails(changedFields, current.version(), persisted.version()),
            correlationId.currentTraceId()));
    return persisted;
  }

  private static List<String> changedFields(Service before, Service after) {
    List<String> fields = new ArrayList<>();
    if (!before.name().equals(after.name())) fields.add("name");
    if (!java.util.Objects.equals(before.description(), after.description()))
      fields.add("description");
    if (before.durationMinutes() != after.durationMinutes()) fields.add("duration_minutes");
    if (before.bufferAfterMinutes() != after.bufferAfterMinutes())
      fields.add("buffer_after_minutes");
    return List.copyOf(fields);
  }

  private static String updateDetails(List<String> fields, long beforeVersion, long afterVersion) {
    String changedFields =
        fields.stream()
            .map(field -> "\"" + field + "\"")
            .collect(java.util.stream.Collectors.joining(","));
    return "{\"changed_fields\":["
        + changedFields
        + "],\"version_before\":"
        + beforeVersion
        + ",\"version_after\":"
        + afterVersion
        + "}";
  }
}
