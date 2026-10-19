package com.appagendamento.appointments.application;

import com.appagendamento.appointments.api.AgendaRevisionAdvancer;
import com.appagendamento.appointments.api.ClientCancellationCutoffQuery;
import com.appagendamento.appointments.api.TenantAgendaLock;
import com.appagendamento.appointments.domain.Appointment;
import com.appagendamento.appointments.domain.AppointmentNotConfirmedException;
import com.appagendamento.appointments.domain.AppointmentStatus;
import com.appagendamento.appointments.domain.AppointmentVersionConflictException;
import com.appagendamento.appointments.domain.ClientAppointmentAccessException;
import com.appagendamento.appointments.domain.ClientAppointmentCancellationPolicy;
import com.appagendamento.appointments.domain.ClientAppointmentNotFoundException;
import com.appagendamento.appointments.domain.ClientAppointmentPolicyUnavailableException;
import com.appagendamento.appointments.domain.ClientCancellationWindowClosedException;
import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.audit.api.AuditEvent;
import com.appagendamento.audit.api.AuditEventRecorder;
import com.appagendamento.clientaccess.api.ClientAccessIdentityQuery;
import com.appagendamento.clientaccess.api.VerifiedClientIdentity;
import com.appagendamento.clients.api.ClientAccountLookup;
import com.appagendamento.idempotency.api.IdempotencyCoordinator;
import com.appagendamento.idempotency.api.IdempotentCommandResult;
import com.appagendamento.outbox.api.OutboxEvent;
import com.appagendamento.outbox.api.OutboxEventRecorder;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class CancelClientAppointment {

  private final ClientAccessIdentityQuery identities;
  private final ClientAccountLookup clients;
  private final AppointmentRepository appointments;
  private final TenantAgendaLock agendaLock;
  private final ClientCancellationCutoffQuery cutoffQuery;
  private final ClientAppointmentCancellationPolicy cancellationPolicy;
  private final AgendaRevisionAdvancer agendaRevision;
  private final IdempotencyCoordinator idempotency;
  private final ClientAppointmentCancellationDigest digests;
  private final ClientAppointmentCancellationReceiptCodec receiptCodec;
  private final AuditEventRecorder audit;
  private final AuditCorrelationId correlation;
  private final OutboxEventRecorder outbox;
  private final Clock clock;

  public CancelClientAppointment(
      ClientAccessIdentityQuery identities,
      ClientAccountLookup clients,
      AppointmentRepository appointments,
      TenantAgendaLock agendaLock,
      ClientCancellationCutoffQuery cutoffQuery,
      ClientAppointmentCancellationPolicy cancellationPolicy,
      AgendaRevisionAdvancer agendaRevision,
      IdempotencyCoordinator idempotency,
      ClientAppointmentCancellationDigest digests,
      ClientAppointmentCancellationReceiptCodec receiptCodec,
      AuditEventRecorder audit,
      AuditCorrelationId correlation,
      OutboxEventRecorder outbox,
      Clock clock) {
    this.identities = identities;
    this.clients = clients;
    this.appointments = appointments;
    this.agendaLock = agendaLock;
    this.cutoffQuery = cutoffQuery;
    this.cancellationPolicy = cancellationPolicy;
    this.agendaRevision = agendaRevision;
    this.idempotency = idempotency;
    this.digests = digests;
    this.receiptCodec = receiptCodec;
    this.audit = audit;
    this.correlation = correlation;
    this.outbox = outbox;
    this.clock = clock;
  }

  public IdempotentCommandResult<ClientAppointmentCancellationReceipt> execute(
      String rawCookieHeader, String key, ClientAppointmentCancellationCommand command) {
    VerifiedClientIdentity identity = requireIdentity(rawCookieHeader);
    UUID clientId = resolveClient(identity);
    appointments
        .findClientAppointment(identity.tenantId(), clientId, command.appointmentId())
        .orElseThrow(ClientAppointmentNotFoundException::new);
    String scope = "appointments.client.cancel.v1." + clientId;
    String digest = digests.calculate(identity.tenantId(), clientId, command);
    return idempotency.execute(
        identity.tenantId(),
        scope,
        key,
        digest,
        () -> revalidate(rawCookieHeader, identity, clientId, command.appointmentId()),
        operationId -> cancel(identity, clientId, command, operationId),
        receiptCodec);
  }

  private ClientAppointmentCancellationReceipt cancel(
      VerifiedClientIdentity identity,
      UUID clientId,
      ClientAppointmentCancellationCommand command,
      UUID operationId) {
    agendaLock.acquire(identity.tenantId());
    Appointment current =
        appointments
            .findForReschedule(identity.tenantId(), command.appointmentId())
            .filter(value -> value.clientId().equals(clientId))
            .orElseThrow(ClientAppointmentNotFoundException::new);
    if (current.status() != AppointmentStatus.CONFIRMED) {
      throw new AppointmentNotConfirmedException();
    }
    if (current.version() != command.expectedVersion()) {
      throw new AppointmentVersionConflictException();
    }
    java.util.OptionalInt cutoff;
    try {
      cutoff = cutoffQuery.cancellationCutoffHours(identity.tenantId());
    } catch (RuntimeException exception) {
      throw new ClientAppointmentPolicyUnavailableException();
    }
    if (cutoff.isEmpty()) throw new ClientAppointmentPolicyUnavailableException();
    var now = clock.instant();
    if (!cancellationPolicy.allows(current.startsAt(), now, cutoff.getAsInt())) {
      throw new ClientCancellationWindowClosedException();
    }
    if (!appointments.transitionClientCancellation(current, clientId, command.expectedVersion())) {
      throw new AppointmentVersionConflictException();
    }
    agendaRevision.advance(identity.tenantId());
    long newVersion = current.version() + 1;
    UUID traceId = correlation.currentTraceId();
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            identity.tenantId(),
            clientId,
            "CLIENT",
            "VERIFIED_CLIENT",
            "APPOINTMENT_CANCELLED_BY_CLIENT",
            "APPOINTMENT",
            current.appointmentId(),
            "COMMITTED",
            traceId,
            traceId,
            "{\"changed_fields\":[\"status\"],\"version_before\":"
                + current.version()
                + ",\"version_after\":"
                + newVersion
                + "}"));
    outbox.record(
        new OutboxEvent(
            UUID.randomUUID(),
            identity.tenantId(),
            "APPOINTMENT",
            current.appointmentId(),
            newVersion,
            "AppointmentCancelled",
            1,
            operationId,
            traceId,
            "{\"appointmentId\":\""
                + current.appointmentId()
                + "\",\"version\":"
                + newVersion
                + "}"));
    return new ClientAppointmentCancellationReceipt(
        current.appointmentId(), operationId, "CANCELLED_BY_CLIENT", newVersion);
  }

  private void revalidate(
      String rawCookieHeader,
      VerifiedClientIdentity expectedIdentity,
      UUID expectedClientId,
      UUID appointmentId) {
    VerifiedClientIdentity current = requireIdentity(rawCookieHeader);
    if (!current.equals(expectedIdentity) || !resolveClient(current).equals(expectedClientId)) {
      throw new ClientAppointmentAccessException();
    }
    appointments
        .findClientAppointment(current.tenantId(), expectedClientId, appointmentId)
        .orElseThrow(ClientAppointmentNotFoundException::new);
  }

  private VerifiedClientIdentity requireIdentity(String rawCookieHeader) {
    return identities
        .currentIdentity(rawCookieHeader)
        .orElseThrow(ClientAppointmentAccessException::new);
  }

  private UUID resolveClient(VerifiedClientIdentity identity) {
    return clients
        .findClientId(identity.tenantId(), identity.normalizedPhone())
        .orElseThrow(ClientAppointmentAccessException::new);
  }
}
