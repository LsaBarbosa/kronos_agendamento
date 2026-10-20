package com.appagendamento.clients.application;

import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.audit.api.AuditEvent;
import com.appagendamento.audit.api.AuditEventRecorder;
import com.appagendamento.clients.api.ClientNoShowFactsQuery;
import com.appagendamento.idempotency.api.IdempotencyCoordinator;
import com.appagendamento.idempotency.api.IdempotentCommandResult;
import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.outbox.api.OutboxEvent;
import com.appagendamento.outbox.api.OutboxEventRecorder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class LiftClientBookingRestriction {

  private final AdminAccessPolicy accessPolicy;
  private final ClientBookingRestrictionCommandRepository restrictions;
  private final ClientNoShowFactsQuery facts;
  private final IdempotencyCoordinator idempotency;
  private final ClientRestrictionLiftReceiptCodec codec;
  private final AuditEventRecorder audit;
  private final AuditCorrelationId correlation;
  private final OutboxEventRecorder outbox;
  private final Clock clock;

  public LiftClientBookingRestriction(
      AdminAccessPolicy accessPolicy,
      ClientBookingRestrictionCommandRepository restrictions,
      ClientNoShowFactsQuery facts,
      IdempotencyCoordinator idempotency,
      ClientRestrictionLiftReceiptCodec codec,
      AuditEventRecorder audit,
      AuditCorrelationId correlation,
      OutboxEventRecorder outbox,
      Clock clock) {
    this.accessPolicy = accessPolicy;
    this.restrictions = restrictions;
    this.facts = facts;
    this.idempotency = idempotency;
    this.codec = codec;
    this.audit = audit;
    this.correlation = correlation;
    this.outbox = outbox;
    this.clock = clock;
  }

  public IdempotentCommandResult<ClientRestrictionLiftReceipt> execute(
      AuthenticatedAdmin actor,
      String key,
      UUID clientId,
      UUID restrictionId,
      long expectedVersion,
      String rawReason) {
    requireCurrent(actor);
    String reason = rawReason == null ? "" : rawReason.strip();
    if (clientId == null
        || restrictionId == null
        || expectedVersion < 1
        || reason.isEmpty()
        || reason.length() > 500) {
      throw new IllegalArgumentException("Dados de desbloqueio inválidos");
    }
    String digest = digest(actor.tenantId(), clientId, restrictionId, expectedVersion, reason);
    return idempotency.execute(
        actor.tenantId(),
        "clients.booking-restriction.lift.v1",
        key,
        digest,
        () -> requireCurrent(actor),
        operationId -> lift(actor, clientId, restrictionId, expectedVersion, reason, operationId),
        codec);
  }

  private ClientRestrictionLiftReceipt lift(
      AuthenticatedAdmin actor,
      UUID clientId,
      UUID restrictionId,
      long expectedVersion,
      String reason,
      UUID operationId) {
    restrictions.lockTenant(actor.tenantId());
    var current =
        restrictions
            .active(actor.tenantId(), clientId, restrictionId)
            .orElseThrow(ClientRestrictionNotFoundException::new);
    if (current.version() != expectedVersion) throw new ClientRestrictionVersionConflictException();
    var asOf = clock.instant();
    var windowStart = asOf.atZone(ZoneOffset.UTC).minusMonths(12).toInstant();
    var page =
        facts
            .find(actor.tenantId(), clientId, asOf, windowStart, null, null, 1)
            .orElseThrow(ClientRestrictionNotFoundException::new);
    if (!restrictions.lift(
        actor.tenantId(),
        clientId,
        restrictionId,
        expectedVersion,
        actor.adminUserId(),
        asOf,
        Math.toIntExact(page.count()),
        reason)) {
      throw new ClientRestrictionVersionConflictException();
    }
    long newVersion = expectedVersion + 1;
    UUID traceId = correlation.currentTraceId();
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            actor.tenantId(),
            actor.adminUserId(),
            "ADMIN",
            "TENANT_ADMIN",
            "CLIENT_SELF_BOOKING_UNBLOCKED",
            "CLIENT_RESTRICTION",
            restrictionId,
            "COMMITTED",
            traceId,
            traceId,
            "{\"changed_fields\":[\"self_booking_restriction\"],\"version_before\":"
                + expectedVersion
                + ",\"version_after\":"
                + newVersion
                + ",\"reason_provided\":true}"));
    outbox.record(
        new OutboxEvent(
            UUID.randomUUID(),
            actor.tenantId(),
            "CLIENT_RESTRICTION",
            restrictionId,
            newVersion,
            "ClientSelfBookingUnblocked",
            1,
            operationId,
            traceId,
            "{\"clientId\":\""
                + clientId
                + "\",\"restrictionId\":\""
                + restrictionId
                + "\",\"version\":"
                + newVersion
                + "}"));
    return new ClientRestrictionLiftReceipt(restrictionId, "LIFTED", newVersion);
  }

  private void requireCurrent(AuthenticatedAdmin actor) {
    if (actor == null || !accessPolicy.isCurrent(actor)) {
      throw new ClientRestrictionAccessDeniedException();
    }
  }

  private static String digest(
      UUID tenantId, UUID clientId, UUID restrictionId, long version, String reason) {
    String value =
        "clients.booking-restriction.lift.canonical.v1\n"
            + tenantId
            + "\n"
            + clientId
            + "\n"
            + restrictionId
            + "\n"
            + version
            + "\n"
            + reason;
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 indisponível", exception);
    }
  }
}
