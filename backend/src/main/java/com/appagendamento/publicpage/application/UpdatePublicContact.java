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
public class UpdatePublicContact {

  private final PublicationRepository publications;
  private final AuditEventRecorder audit;
  private final AuditCorrelationId correlationId;
  private final AdminAccessPolicy accessPolicy;
  private final IdempotencyCoordinator idempotency;
  private final CommandResultCodec<PublicationState> resultCodec;

  public UpdatePublicContact(
      PublicationRepository publications,
      AuditEventRecorder audit,
      AuditCorrelationId correlationId,
      AdminAccessPolicy accessPolicy,
      IdempotencyCoordinator idempotency,
      CommandResultCodec<PublicationState> resultCodec) {
    this.publications = publications;
    this.audit = audit;
    this.correlationId = correlationId;
    this.accessPolicy = accessPolicy;
    this.idempotency = idempotency;
    this.resultCodec = resultCodec;
  }

  public IdempotentCommandResult<PublicationState> execute(
      AuthenticatedAdmin actor, String key, String phone, long expectedVersion) {
    String normalized = phone == null ? "" : phone.strip();
    String digest =
        PublicationCommandDigest.calculate(
            actor.tenantId(), "contact", expectedVersion, normalized);
    return idempotency.execute(
        actor.tenantId(),
        "tenant.public-profile.contact.v1",
        key,
        digest,
        () -> revalidate(actor),
        operationId -> update(actor, normalized, expectedVersion),
        resultCodec);
  }

  private void revalidate(AuthenticatedAdmin actor) {
    if (!accessPolicy.isCurrent(actor) || publications.findState(actor.tenantId()).isEmpty()) {
      throw new PublicationNotFoundException();
    }
  }

  private PublicationState update(AuthenticatedAdmin actor, String phone, long expectedVersion) {
    PublicationState current =
        publications.findState(actor.tenantId()).orElseThrow(PublicationNotFoundException::new);
    if (current.version() != expectedVersion) {
      throw new PublicationVersionConflictException();
    }
    PublicationState updated = current.withContactPhone(phone);
    if (current.contactPhone() != null && current.contactPhone().equals(phone)) {
      return current;
    }
    if (!publications.updateContactPhone(actor.tenantId(), phone, expectedVersion)) {
      throw new PublicationVersionConflictException();
    }
    updated =
        publications.findState(actor.tenantId()).orElseThrow(PublicationNotFoundException::new);
    UUID traceId = correlationId.currentTraceId();
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            actor.tenantId(),
            actor.adminUserId(),
            "ADMIN",
            "TENANT_ADMIN",
            "PUBLIC_PROFILE_UPDATED",
            "TENANT",
            actor.tenantId(),
            "COMMITTED",
            traceId,
            traceId,
            "{\"changed_fields\":[\"contact_phone\"],\"version_before\":"
                + current.version()
                + ",\"version_after\":"
                + updated.version()
                + "}"));
    return updated;
  }
}
