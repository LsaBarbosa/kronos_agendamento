package com.appagendamento.publicpage.application;

import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.audit.api.AuditEvent;
import com.appagendamento.audit.api.AuditEventRecorder;
import com.appagendamento.idempotency.api.CommandResultCodec;
import com.appagendamento.idempotency.api.IdempotencyCoordinator;
import com.appagendamento.idempotency.api.IdempotentCommandResult;
import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.publicpage.domain.PublicationState;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ChangePublicationStatus {

  private final PublicationRepository publications;
  private final EvaluatePublicationReadiness readiness;
  private final AuditEventRecorder audit;
  private final AuditCorrelationId correlationId;
  private final AdminAccessPolicy accessPolicy;
  private final IdempotencyCoordinator idempotency;
  private final CommandResultCodec<PublicationState> resultCodec;

  public ChangePublicationStatus(
      PublicationRepository publications,
      EvaluatePublicationReadiness readiness,
      AuditEventRecorder audit,
      AuditCorrelationId correlationId,
      AdminAccessPolicy accessPolicy,
      IdempotencyCoordinator idempotency,
      CommandResultCodec<PublicationState> resultCodec) {
    this.publications = publications;
    this.readiness = readiness;
    this.audit = audit;
    this.correlationId = correlationId;
    this.accessPolicy = accessPolicy;
    this.idempotency = idempotency;
    this.resultCodec = resultCodec;
  }

  public IdempotentCommandResult<PublicationState> execute(
      AuthenticatedAdmin actor, String key, boolean published, long expectedVersion) {
    UUID tenantId = actor.tenantId();
    String digest =
        PublicationCommandDigest.calculate(
            tenantId, "status", expectedVersion, Boolean.toString(published));
    return idempotency.execute(
        tenantId,
        "tenant.publication.status.v1",
        key,
        digest,
        () -> revalidate(actor),
        operationId -> change(actor, published, expectedVersion),
        resultCodec);
  }

  private void revalidate(AuthenticatedAdmin actor) {
    if (!accessPolicy.isCurrent(actor) || publications.findState(actor.tenantId()).isEmpty()) {
      throw new PublicationNotFoundException();
    }
  }

  private PublicationState change(
      AuthenticatedAdmin actor, boolean published, long expectedVersion) {
    UUID tenantId = actor.tenantId();
    PublicationState current =
        publications.findState(tenantId).orElseThrow(PublicationNotFoundException::new);
    if (current.version() != expectedVersion) {
      throw new PublicationVersionConflictException();
    }
    if (published) {
      PublicationReadiness currentReadiness = readiness.execute(tenantId);
      if (!currentReadiness.ready()) {
        throw new PublicationNotReadyException(currentReadiness.blockers());
      }
    }
    if (current.published() == published) {
      return current;
    }
    if (!publications.updatePublished(tenantId, published, expectedVersion)) {
      throw new PublicationVersionConflictException();
    }
    PublicationState updated =
        publications.findState(tenantId).orElseThrow(PublicationNotFoundException::new);
    UUID traceId = correlationId.currentTraceId();
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            tenantId,
            actor.adminUserId(),
            "ADMIN",
            "TENANT_ADMIN",
            published ? "PUBLICATION_PUBLISHED" : "PUBLICATION_UNPUBLISHED",
            "TENANT",
            tenantId,
            "COMMITTED",
            traceId,
            traceId,
            "{\"changed_fields\":[\"publication\"],\"version_before\":"
                + current.version()
                + ",\"version_after\":"
                + updated.version()
                + "}"));
    return updated;
  }
}
