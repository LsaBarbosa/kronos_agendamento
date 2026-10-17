package com.appagendamento.appointments.application;

import com.appagendamento.appointments.api.AdministrativeBookingAssessment;
import com.appagendamento.appointments.api.AdministrativeBookingPolicyQuery;
import com.appagendamento.appointments.api.TenantAgendaLock;
import com.appagendamento.appointments.domain.AdminAppointmentAccessDeniedException;
import com.appagendamento.appointments.domain.AdminAppointmentNotFoundException;
import com.appagendamento.appointments.domain.AdministrativeOverridePolicy;
import com.appagendamento.appointments.domain.AdministrativeScheduleConflictException;
import com.appagendamento.appointments.domain.Appointment;
import com.appagendamento.appointments.domain.AppointmentStatus;
import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.audit.api.AuditEvent;
import com.appagendamento.audit.api.AuditEventRecorder;
import com.appagendamento.catalog.api.ServiceAvailabilityQuery;
import com.appagendamento.catalog.api.ServiceAvailabilityView;
import com.appagendamento.clients.api.AdministrativeClientRegistry;
import com.appagendamento.clients.api.ClientBookingRestrictionQuery;
import com.appagendamento.clients.api.ClientBookingRestrictionStatus;
import com.appagendamento.clients.api.PublicClientRegistry;
import com.appagendamento.idempotency.api.IdempotencyCoordinator;
import com.appagendamento.idempotency.api.IdempotentCommandResult;
import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.outbox.api.OutboxEvent;
import com.appagendamento.outbox.api.OutboxEventRecorder;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class CreateAdministrativeAppointment {

  public static final String COMMAND_SCOPE = "appointments.admin.create.v1";

  private final AdminAccessPolicy accessPolicy;
  private final TenantAgendaLock agendaLock;
  private final AdministrativeBookingPolicyQuery bookingPolicy;
  private final ServiceAvailabilityQuery services;
  private final AdministrativeClientRegistry clients;
  private final PublicClientRegistry clientValidation;
  private final ClientBookingRestrictionQuery restrictions;
  private final AppointmentRepository appointments;
  private final IdempotencyCoordinator idempotency;
  private final AdministrativeAppointmentCommandDigest digests;
  private final AdministrativeAppointmentReceiptCodec receiptCodec;
  private final AuditEventRecorder audit;
  private final AuditCorrelationId correlation;
  private final OutboxEventRecorder outbox;
  private final Clock clock;

  public CreateAdministrativeAppointment(
      AdminAccessPolicy accessPolicy,
      TenantAgendaLock agendaLock,
      AdministrativeBookingPolicyQuery bookingPolicy,
      ServiceAvailabilityQuery services,
      AdministrativeClientRegistry clients,
      PublicClientRegistry clientValidation,
      ClientBookingRestrictionQuery restrictions,
      AppointmentRepository appointments,
      IdempotencyCoordinator idempotency,
      AdministrativeAppointmentCommandDigest digests,
      AdministrativeAppointmentReceiptCodec receiptCodec,
      AuditEventRecorder audit,
      AuditCorrelationId correlation,
      OutboxEventRecorder outbox,
      Clock clock) {
    this.accessPolicy = accessPolicy;
    this.agendaLock = agendaLock;
    this.bookingPolicy = bookingPolicy;
    this.services = services;
    this.clients = clients;
    this.clientValidation = clientValidation;
    this.restrictions = restrictions;
    this.appointments = appointments;
    this.idempotency = idempotency;
    this.digests = digests;
    this.receiptCodec = receiptCodec;
    this.audit = audit;
    this.correlation = correlation;
    this.outbox = outbox;
    this.clock = clock;
  }

  public IdempotentCommandResult<AdministrativeAppointmentReceipt> execute(
      AuthenticatedAdmin actor, String key, CreateAdministrativeAppointmentCommand command) {
    requireCurrent(actor);
    String name = null;
    String phone = null;
    if (command.clientId() == null) {
      name = clientValidation.canonicalizeName(command.clientName());
      phone = clientValidation.normalizePhone(command.clientPhone());
    }
    String reason = normalizeReason(command.reason());
    if (command.overridePolicies().isEmpty() != (reason == null)) {
      throw new IllegalArgumentException("Motivo só é permitido e obrigatório para uma exceção");
    }
    String digest = digests.calculate(actor.tenantId(), command, name, phone, reason);
    String canonicalName = name;
    String canonicalPhone = phone;
    return idempotency.execute(
        actor.tenantId(),
        COMMAND_SCOPE,
        key,
        digest,
        () -> requireCurrent(actor),
        operationId -> create(actor, command, canonicalName, canonicalPhone, reason, operationId),
        receiptCodec);
  }

  private AdministrativeAppointmentReceipt create(
      AuthenticatedAdmin actor,
      CreateAdministrativeAppointmentCommand command,
      String name,
      String phone,
      String reason,
      UUID operationId) {
    agendaLock.acquire(actor.tenantId());
    AdministrativeBookingAssessment assessment =
        bookingPolicy.assess(
            actor.tenantId(),
            command.serviceId(),
            command.startsAt(),
            command.expectedScheduleVersion(),
            command
                .overridePolicies()
                .contains(AdministrativeOverridePolicy.OUTSIDE_WEEKLY_SCHEDULE),
            command.overridePolicies().contains(AdministrativeOverridePolicy.MIN_NOTICE));
    List<AdministrativeOverridePolicy> requested =
        command.overridePolicies().stream().sorted().toList();
    if (!assessment.violatedPolicies().stream().sorted().toList().equals(requested)) {
      throw new IllegalArgumentException(
          "As exceções selecionadas não correspondem às regras violadas");
    }
    ServiceAvailabilityView service =
        services
            .findForBooking(actor.tenantId(), command.serviceId())
            .filter(value -> value.tenantId().equals(actor.tenantId()) && value.active())
            .orElseThrow(AdministrativeScheduleConflictException::new);

    UUID clientId;
    String snapshotName;
    if (command.clientId() != null) {
      var client =
          clients
              .find(actor.tenantId(), command.clientId())
              .orElseThrow(AdminAppointmentNotFoundException::new);
      if (!client.tenantId().equals(actor.tenantId()) || !client.status().equals("ACTIVE")) {
        throw new AdminAppointmentNotFoundException();
      }
      clientId = client.clientId();
      snapshotName = client.displayName();
    } else {
      clientId = clients.findOrCreate(actor.tenantId(), name, phone);
      snapshotName =
          clients
              .find(actor.tenantId(), clientId)
              .orElseThrow(AdminAppointmentNotFoundException::new)
              .displayName();
    }
    ClientBookingRestrictionStatus restrictionStatus =
        restrictions.status(actor.tenantId(), clientId);
    Appointment appointment =
        new Appointment(
            operationId,
            actor.tenantId(),
            clientId,
            service.serviceId(),
            assessment.startsAt(),
            assessment.serviceEndsAt(),
            assessment.occupiedUntil(),
            assessment.timeZone(),
            snapshotName,
            service.name(),
            service.durationMinutes(),
            service.bufferAfterMinutes(),
            null,
            null,
            AppointmentStatus.CONFIRMED,
            clock.instant(),
            1);
    UUID traceId = correlation.currentTraceId();
    appointments.insertConfirmed(
        appointment,
        operationId.toString(),
        traceId.toString(),
        AppointmentCreationContext.administrative(reason));
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            actor.tenantId(),
            actor.adminUserId(),
            "ADMIN",
            "TENANT_ADMIN",
            "APPOINTMENT_CONFIRMED",
            "APPOINTMENT",
            operationId,
            "COMMITTED",
            traceId,
            traceId,
            auditDetails(requested, reason != null, restrictionStatus)));
    outbox.record(
        new OutboxEvent(
            UUID.randomUUID(),
            actor.tenantId(),
            "APPOINTMENT",
            operationId,
            1,
            "AppointmentConfirmed",
            1,
            operationId,
            traceId,
            "{\"appointmentId\":\"" + operationId + "\",\"version\":1}"));
    return new AdministrativeAppointmentReceipt(
        operationId,
        operationId,
        AppointmentStatus.CONFIRMED,
        service.name(),
        command.startsAt(),
        command.startsAt().plusMinutes(service.durationMinutes()),
        assessment
            .occupiedUntil()
            .atZone(java.time.ZoneId.of(assessment.timeZone()))
            .toOffsetDateTime(),
        assessment.timeZone());
  }

  private void requireCurrent(AuthenticatedAdmin actor) {
    if (actor == null || !accessPolicy.isCurrent(actor)) {
      throw new AdminAppointmentAccessDeniedException();
    }
  }

  private static String normalizeReason(String value) {
    if (value == null) return null;
    String reason = value.strip();
    if (reason.isEmpty()) return null;
    if (reason.length() > 500) throw new IllegalArgumentException("Motivo excede 500 caracteres");
    return reason;
  }

  private static String auditDetails(
      List<AdministrativeOverridePolicy> overrides,
      boolean reasonProvided,
      ClientBookingRestrictionStatus restrictionStatus) {
    String policies =
        overrides.stream()
            .map(policy -> "\"" + policy.name() + "\"")
            .collect(java.util.stream.Collectors.joining(","));
    return "{\"changed_fields\":[\"appointment\"],\"version_before\":0,\"version_after\":1,\"override_policies\":["
        + policies
        + "],\"reason_provided\":"
        + reasonProvided
        + ",\"client_restriction_status\":\""
        + restrictionStatus.name()
        + "\"}";
  }
}
