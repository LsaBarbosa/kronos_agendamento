package com.appagendamento.appointments.application;

import com.appagendamento.appointments.api.TenantAgendaLock;
import com.appagendamento.appointments.domain.AdminAppointmentAccessDeniedException;
import com.appagendamento.appointments.domain.AdminAppointmentNotFoundException;
import com.appagendamento.appointments.domain.Appointment;
import com.appagendamento.appointments.domain.AppointmentNoShowNotCorrectableException;
import com.appagendamento.appointments.domain.AppointmentStatus;
import com.appagendamento.appointments.domain.AppointmentVersionConflictException;
import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.audit.api.AuditEvent;
import com.appagendamento.audit.api.AuditEventRecorder;
import com.appagendamento.idempotency.api.IdempotencyCoordinator;
import com.appagendamento.idempotency.api.IdempotentCommandResult;
import com.appagendamento.idempotency.application.IdempotencyKey;
import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.outbox.api.OutboxEvent;
import com.appagendamento.outbox.api.OutboxEventRecorder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class CorrectAdministrativeNoShow {

  private final AdminAccessPolicy accessPolicy;
  private final TenantAgendaLock agendaLock;
  private final AppointmentRepository appointments;
  private final IdempotencyCoordinator idempotency;
  private final AdministrativeAppointmentOutcomeReceiptCodec receiptCodec;
  private final AuditEventRecorder audit;
  private final AuditCorrelationId correlation;
  private final OutboxEventRecorder outbox;

  public CorrectAdministrativeNoShow(
      AdminAccessPolicy accessPolicy,
      TenantAgendaLock agendaLock,
      AppointmentRepository appointments,
      IdempotencyCoordinator idempotency,
      AdministrativeAppointmentOutcomeReceiptCodec receiptCodec,
      AuditEventRecorder audit,
      AuditCorrelationId correlation,
      OutboxEventRecorder outbox) {
    this.accessPolicy = accessPolicy;
    this.agendaLock = agendaLock;
    this.appointments = appointments;
    this.idempotency = idempotency;
    this.receiptCodec = receiptCodec;
    this.audit = audit;
    this.correlation = correlation;
    this.outbox = outbox;
  }

  public IdempotentCommandResult<AdministrativeAppointmentOutcomeReceipt> execute(
      AuthenticatedAdmin actor,
      String key,
      UUID appointmentId,
      long expectedVersion,
      String reason) {
    requireCurrent(actor);
    appointments
        .findAdminAppointment(actor.tenantId(), appointmentId)
        .orElseThrow(AdminAppointmentNotFoundException::new);
    String normalizedReason = reason == null ? "" : reason.strip();
    if (expectedVersion < 1 || normalizedReason.isEmpty() || normalizedReason.length() > 500) {
      throw new IllegalArgumentException("Versão ou motivo de correção inválidos");
    }
    String digest =
        IdempotencyKey.sha256(
            ("appointments.admin.correct-no-show.canonical.v1\n"
                    + actor.tenantId()
                    + "\n"
                    + appointmentId
                    + "\n"
                    + expectedVersion
                    + "\n"
                    + normalizedReason)
                .getBytes(StandardCharsets.UTF_8));
    return idempotency.execute(
        actor.tenantId(),
        "appointments.admin.correct-no-show.v1",
        key,
        digest,
        () -> {
          requireCurrent(actor);
          appointments
              .findAdminAppointment(actor.tenantId(), appointmentId)
              .orElseThrow(AdminAppointmentNotFoundException::new);
        },
        operationId ->
            correct(actor, appointmentId, expectedVersion, normalizedReason, operationId),
        receiptCodec);
  }

  private AdministrativeAppointmentOutcomeReceipt correct(
      AuthenticatedAdmin actor,
      UUID appointmentId,
      long expectedVersion,
      String reason,
      UUID operationId) {
    agendaLock.acquire(actor.tenantId());
    Appointment current =
        appointments
            .findForReschedule(actor.tenantId(), appointmentId)
            .orElseThrow(AdminAppointmentNotFoundException::new);
    if (current.status() != AppointmentStatus.NO_SHOW) {
      throw new AppointmentNoShowNotCorrectableException();
    }
    if (current.version() != expectedVersion) {
      throw new AppointmentVersionConflictException();
    }
    if (!appointments.correctNoShow(current, expectedVersion, reason)) {
      throw new AppointmentVersionConflictException();
    }
    UUID traceId = correlation.currentTraceId();
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            actor.tenantId(),
            actor.adminUserId(),
            "ADMIN",
            "TENANT_ADMIN",
            "APPOINTMENT_NO_SHOW_CORRECTED",
            "APPOINTMENT",
            appointmentId,
            "COMMITTED",
            traceId,
            traceId,
            "{\"changed_fields\":[\"status\"],\"version_before\":"
                + expectedVersion
                + ",\"version_after\":"
                + (expectedVersion + 1)
                + ",\"reason_provided\":true}"));
    outbox.record(
        new OutboxEvent(
            UUID.randomUUID(),
            actor.tenantId(),
            "APPOINTMENT",
            appointmentId,
            expectedVersion + 1,
            "ClientNoShowCorrected",
            1,
            operationId,
            traceId,
            "{\"appointmentId\":\""
                + appointmentId
                + "\",\"version\":"
                + (expectedVersion + 1)
                + "}"));
    return new AdministrativeAppointmentOutcomeReceipt(
        appointmentId, operationId, AppointmentStatus.COMPLETED, expectedVersion + 1);
  }

  private void requireCurrent(AuthenticatedAdmin actor) {
    if (actor == null || !accessPolicy.isCurrent(actor)) {
      throw new AdminAppointmentAccessDeniedException();
    }
  }
}
