package com.appagendamento.appointments.application;

import com.appagendamento.appointments.api.AdministrativeBookingAssessment;
import com.appagendamento.appointments.api.AdministrativeBookingPolicyQuery;
import com.appagendamento.appointments.api.AgendaRevisionAdvancer;
import com.appagendamento.appointments.api.TenantAgendaLock;
import com.appagendamento.appointments.domain.AdminAppointmentAccessDeniedException;
import com.appagendamento.appointments.domain.AdminAppointmentNotFoundException;
import com.appagendamento.appointments.domain.Appointment;
import com.appagendamento.appointments.domain.AppointmentNotReschedulableException;
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
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RescheduleAdministrativeAppointment {

  public static final String COMMAND_SCOPE = "appointments.admin.reschedule.v1";

  private final AdminAccessPolicy accessPolicy;
  private final TenantAgendaLock agendaLock;
  private final AppointmentRepository appointments;
  private final AgendaRevisionAdvancer agendaRevision;
  private final AdministrativeBookingPolicyQuery bookingPolicy;
  private final IdempotencyCoordinator idempotency;
  private final AdministrativeRescheduleCommandDigest digests;
  private final AdministrativeRescheduleReceiptCodec receiptCodec;
  private final AuditEventRecorder audit;
  private final AuditCorrelationId correlation;
  private final OutboxEventRecorder outbox;

  public RescheduleAdministrativeAppointment(
      AdminAccessPolicy accessPolicy,
      TenantAgendaLock agendaLock,
      AppointmentRepository appointments,
      AgendaRevisionAdvancer agendaRevision,
      AdministrativeBookingPolicyQuery bookingPolicy,
      IdempotencyCoordinator idempotency,
      AdministrativeRescheduleCommandDigest digests,
      AdministrativeRescheduleReceiptCodec receiptCodec,
      AuditEventRecorder audit,
      AuditCorrelationId correlation,
      OutboxEventRecorder outbox) {
    this.accessPolicy = accessPolicy;
    this.agendaLock = agendaLock;
    this.appointments = appointments;
    this.agendaRevision = agendaRevision;
    this.bookingPolicy = bookingPolicy;
    this.idempotency = idempotency;
    this.digests = digests;
    this.receiptCodec = receiptCodec;
    this.audit = audit;
    this.correlation = correlation;
    this.outbox = outbox;
  }

  public IdempotentCommandResult<AdministrativeRescheduleReceipt> execute(
      AuthenticatedAdmin actor, String key, RescheduleAdministrativeAppointmentCommand command) {
    requireCurrent(actor);
    appointments
        .findAdminAppointment(actor.tenantId(), command.appointmentId())
        .orElseThrow(AdminAppointmentNotFoundException::new);
    String reason = normalizeReason(command.reason());
    if (command.overridePolicies().isEmpty() != (reason == null)) {
      throw new IllegalArgumentException("Motivo só é permitido e obrigatório para um override");
    }
    String digest = digests.calculate(actor.tenantId(), command, reason);
    return idempotency.execute(
        actor.tenantId(),
        COMMAND_SCOPE,
        key,
        digest,
        () -> {
          requireCurrent(actor);
          appointments
              .findAdminAppointment(actor.tenantId(), command.appointmentId())
              .orElseThrow(AdminAppointmentNotFoundException::new);
        },
        operationId -> reschedule(actor, command, reason, operationId),
        receiptCodec);
  }

  private AdministrativeRescheduleReceipt reschedule(
      AuthenticatedAdmin actor,
      RescheduleAdministrativeAppointmentCommand command,
      String reason,
      UUID operationId) {
    agendaLock.acquire(actor.tenantId());
    Appointment current =
        appointments
            .findForReschedule(actor.tenantId(), command.appointmentId())
            .orElseThrow(AdminAppointmentNotFoundException::new);
    if (current.status() != AppointmentStatus.CONFIRMED) {
      throw new AppointmentNotReschedulableException();
    }
    if (current.version() != command.expectedVersion()) {
      throw new AppointmentVersionConflictException();
    }
    AdministrativeBookingAssessment assessment =
        bookingPolicy.assessReschedule(
            actor.tenantId(),
            current.appointmentId(),
            current.serviceId(),
            current.serviceDurationMinutes(),
            current.serviceBufferAfterMinutes(),
            command.startsAt(),
            command.expectedScheduleRevision(),
            command
                .overridePolicies()
                .contains(
                    com.appagendamento.appointments.domain.AdministrativeOverridePolicy
                        .OUTSIDE_WEEKLY_SCHEDULE),
            command
                .overridePolicies()
                .contains(
                    com.appagendamento.appointments.domain.AdministrativeOverridePolicy
                        .MIN_NOTICE));
    List<com.appagendamento.appointments.domain.AdministrativeOverridePolicy> requested =
        command.overridePolicies().stream().sorted().toList();
    if (!assessment.violatedPolicies().stream().sorted().toList().equals(requested)) {
      throw new IllegalArgumentException(
          "As exceções selecionadas não correspondem às regras violadas");
    }
    if (!appointments.reschedule(current, command.expectedVersion(), assessment, reason)) {
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
            "APPOINTMENT_RESCHEDULED",
            "APPOINTMENT",
            current.appointmentId(),
            "COMMITTED",
            traceId,
            traceId,
            auditDetails(current.version(), newVersion, requested, reason != null)));
    outbox.record(
        new OutboxEvent(
            UUID.randomUUID(),
            actor.tenantId(),
            "APPOINTMENT",
            current.appointmentId(),
            newVersion,
            "AppointmentRescheduled",
            1,
            operationId,
            traceId,
            "{\"appointmentId\":\""
                + current.appointmentId()
                + "\",\"version\":"
                + newVersion
                + "}"));
    ZoneId zone = ZoneId.of(assessment.timeZone());
    return new AdministrativeRescheduleReceipt(
        current.appointmentId(),
        operationId,
        newVersion,
        assessment.startsAt().atZone(zone).toOffsetDateTime(),
        assessment.serviceEndsAt().atZone(zone).toOffsetDateTime(),
        assessment.occupiedUntil().atZone(zone).toOffsetDateTime(),
        zone.getId());
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

  private static String auditDetails(
      long before,
      long after,
      List<com.appagendamento.appointments.domain.AdministrativeOverridePolicy> overrides,
      boolean reasonProvided) {
    String policies =
        overrides.stream()
            .map(policy -> "\"" + policy.name() + "\"")
            .collect(java.util.stream.Collectors.joining(","));
    return "{\"changed_fields\":[\"appointment\"],\"version_before\":"
        + before
        + ",\"version_after\":"
        + after
        + ",\"override_policies\":["
        + policies
        + "],\"reason_provided\":"
        + reasonProvided
        + "}";
  }
}
