package com.appagendamento.appointments.application;

import com.appagendamento.appointments.api.AgendaRevisionAdvancer;
import com.appagendamento.appointments.api.TenantAgendaLock;
import com.appagendamento.appointments.domain.AdminAppointmentAccessDeniedException;
import com.appagendamento.appointments.domain.AdminAppointmentNotFoundException;
import com.appagendamento.appointments.domain.Appointment;
import com.appagendamento.appointments.domain.AppointmentNotConfirmedException;
import com.appagendamento.appointments.domain.AppointmentOutcome;
import com.appagendamento.appointments.domain.AppointmentOutcomeNotYetAllowedException;
import com.appagendamento.appointments.domain.AppointmentStatus;
import com.appagendamento.appointments.domain.AppointmentVersionConflictException;
import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.audit.api.AuditEvent;
import com.appagendamento.audit.api.AuditEventRecorder;
import com.appagendamento.idempotency.api.IdempotencyCoordinator;
import com.appagendamento.idempotency.api.IdempotentCommandResult;
import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.outbox.api.OutboxEvent;
import com.appagendamento.outbox.api.OutboxEventRecorder;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class TransitionAdministrativeAppointment {

  private final AdminAccessPolicy accessPolicy;
  private final TenantAgendaLock agendaLock;
  private final AppointmentRepository appointments;
  private final AgendaRevisionAdvancer agendaRevision;
  private final IdempotencyCoordinator idempotency;
  private final AdministrativeAppointmentOutcomeDigest digests;
  private final AdministrativeAppointmentOutcomeReceiptCodec receiptCodec;
  private final AuditEventRecorder audit;
  private final AuditCorrelationId correlation;
  private final OutboxEventRecorder outbox;
  private final Clock clock;

  public TransitionAdministrativeAppointment(
      AdminAccessPolicy accessPolicy,
      TenantAgendaLock agendaLock,
      AppointmentRepository appointments,
      AgendaRevisionAdvancer agendaRevision,
      IdempotencyCoordinator idempotency,
      AdministrativeAppointmentOutcomeDigest digests,
      AdministrativeAppointmentOutcomeReceiptCodec receiptCodec,
      AuditEventRecorder audit,
      AuditCorrelationId correlation,
      OutboxEventRecorder outbox,
      Clock clock) {
    this.accessPolicy = accessPolicy;
    this.agendaLock = agendaLock;
    this.appointments = appointments;
    this.agendaRevision = agendaRevision;
    this.idempotency = idempotency;
    this.digests = digests;
    this.receiptCodec = receiptCodec;
    this.audit = audit;
    this.correlation = correlation;
    this.outbox = outbox;
    this.clock = clock;
  }

  public IdempotentCommandResult<AdministrativeAppointmentOutcomeReceipt> execute(
      AuthenticatedAdmin actor,
      String key,
      AppointmentOutcome outcome,
      AdministrativeAppointmentOutcomeCommand command) {
    requireCurrent(actor);
    appointments
        .findAdminAppointment(actor.tenantId(), command.appointmentId())
        .orElseThrow(AdminAppointmentNotFoundException::new);
    String reason = normalizeReason(command.reason());
    String scope = "appointments.admin." + outcome.name().toLowerCase() + ".v1";
    String digest = digests.calculate(actor.tenantId(), outcome, command, reason);
    return idempotency.execute(
        actor.tenantId(),
        scope,
        key,
        digest,
        () -> {
          requireCurrent(actor);
          appointments
              .findAdminAppointment(actor.tenantId(), command.appointmentId())
              .orElseThrow(AdminAppointmentNotFoundException::new);
        },
        operationId -> transition(actor, outcome, command, reason, operationId),
        receiptCodec);
  }

  private AdministrativeAppointmentOutcomeReceipt transition(
      AuthenticatedAdmin actor,
      AppointmentOutcome outcome,
      AdministrativeAppointmentOutcomeCommand command,
      String reason,
      UUID operationId) {
    agendaLock.acquire(actor.tenantId());
    Appointment current =
        appointments
            .findForReschedule(actor.tenantId(), command.appointmentId())
            .orElseThrow(AdminAppointmentNotFoundException::new);
    if (current.status() != AppointmentStatus.CONFIRMED) {
      throw new AppointmentNotConfirmedException();
    }
    if (current.version() != command.expectedVersion()) {
      throw new AppointmentVersionConflictException();
    }
    var now = clock.instant();
    if ((outcome == AppointmentOutcome.COMPLETE && now.isBefore(current.serviceEndsAt()))
        || (outcome == AppointmentOutcome.MARK_NO_SHOW && now.isBefore(current.occupiedUntil()))) {
      throw new AppointmentOutcomeNotYetAllowedException();
    }
    if (!appointments.transitionOutcome(current, command.expectedVersion(), outcome, reason)) {
      throw new AppointmentVersionConflictException();
    }
    agendaRevision.advance(actor.tenantId());
    long newVersion = current.version() + 1;
    UUID traceId = correlation.currentTraceId();
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            actor.tenantId(),
            actor.adminUserId(),
            "ADMIN",
            "TENANT_ADMIN",
            outcome.auditAction(),
            "APPOINTMENT",
            current.appointmentId(),
            "COMMITTED",
            traceId,
            traceId,
            "{\"changed_fields\":[\"status\"],\"version_before\":"
                + current.version()
                + ",\"version_after\":"
                + newVersion
                + ",\"reason_provided\":"
                + (reason != null)
                + "}"));
    outbox.record(
        new OutboxEvent(
            UUID.randomUUID(),
            actor.tenantId(),
            "APPOINTMENT",
            current.appointmentId(),
            newVersion,
            outcome.eventType(),
            1,
            operationId,
            traceId,
            "{\"appointmentId\":\""
                + current.appointmentId()
                + "\",\"version\":"
                + newVersion
                + "}"));
    return new AdministrativeAppointmentOutcomeReceipt(
        current.appointmentId(), operationId, outcome.status(), newVersion);
  }

  private void requireCurrent(AuthenticatedAdmin actor) {
    if (actor == null || !accessPolicy.isCurrent(actor)) {
      throw new AdminAppointmentAccessDeniedException();
    }
  }

  private static String normalizeReason(String value) {
    if (value == null) return null;
    String normalized = value.strip();
    if (normalized.isEmpty()) return null;
    if (normalized.length() > 500)
      throw new IllegalArgumentException("Motivo excede 500 caracteres");
    return normalized;
  }
}
