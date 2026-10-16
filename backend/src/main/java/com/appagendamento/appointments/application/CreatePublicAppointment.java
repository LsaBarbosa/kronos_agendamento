package com.appagendamento.appointments.application;

import com.appagendamento.appointments.api.AppointmentSlotValidator;
import com.appagendamento.appointments.api.PublicBookingGate;
import com.appagendamento.appointments.api.TenantAgendaLock;
import com.appagendamento.appointments.api.ValidatedAppointmentSlot;
import com.appagendamento.appointments.domain.Appointment;
import com.appagendamento.appointments.domain.AppointmentStatus;
import com.appagendamento.appointments.domain.PublicAppointmentUnavailableException;
import com.appagendamento.appointments.domain.SlotNoLongerAvailableException;
import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.audit.api.AuditEvent;
import com.appagendamento.audit.api.AuditEventRecorder;
import com.appagendamento.catalog.api.ServiceAvailabilityQuery;
import com.appagendamento.catalog.api.ServiceAvailabilityView;
import com.appagendamento.clients.api.PublicClientRegistry;
import com.appagendamento.idempotency.api.IdempotencyCoordinator;
import com.appagendamento.idempotency.api.IdempotentCommandResult;
import com.appagendamento.outbox.api.OutboxEvent;
import com.appagendamento.outbox.api.OutboxEventRecorder;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class CreatePublicAppointment {

  private final PublicBookingGate gate;
  private final ServiceAvailabilityQuery services;
  private final PublicClientRegistry clients;
  private final AppointmentSlotValidator slots;
  private final TenantAgendaLock agendaLock;
  private final AppointmentRepository appointments;
  private final IdempotencyCoordinator idempotency;
  private final PublicAppointmentReceiptCodec receiptCodec;
  private final AuditEventRecorder audit;
  private final AuditCorrelationId correlation;
  private final OutboxEventRecorder outbox;
  private final Clock clock;

  public CreatePublicAppointment(
      PublicBookingGate gate,
      ServiceAvailabilityQuery services,
      PublicClientRegistry clients,
      AppointmentSlotValidator slots,
      TenantAgendaLock agendaLock,
      AppointmentRepository appointments,
      IdempotencyCoordinator idempotency,
      PublicAppointmentReceiptCodec receiptCodec,
      AuditEventRecorder audit,
      AuditCorrelationId correlation,
      OutboxEventRecorder outbox,
      Clock clock) {
    this.gate = gate;
    this.services = services;
    this.clients = clients;
    this.slots = slots;
    this.agendaLock = agendaLock;
    this.appointments = appointments;
    this.idempotency = idempotency;
    this.receiptCodec = receiptCodec;
    this.audit = audit;
    this.correlation = correlation;
    this.outbox = outbox;
    this.clock = clock;
  }

  public IdempotentCommandResult<PublicAppointmentReceipt> execute(
      String slug,
      String key,
      UUID serviceId,
      OffsetDateTime requestedStart,
      String rawName,
      String rawPhone) {
    UUID tenantId = gate.resolveVisibleTenant(slug);
    String name = clients.canonicalizeName(rawName);
    String phone = clients.normalizePhone(rawPhone);
    String digest =
        PublicAppointmentCommandDigest.create(
            tenantId, slug, serviceId, requestedStart, name, phone);
    return idempotency.execute(
        tenantId,
        "appointments.public.create.v1",
        key,
        digest,
        () -> {
          gate.requireVisibleTenant(slug, tenantId);
          requireActiveService(tenantId, serviceId);
        },
        operationId -> create(tenantId, slug, serviceId, requestedStart, name, phone, operationId),
        receiptCodec);
  }

  private PublicAppointmentReceipt create(
      UUID tenantId,
      String slug,
      UUID serviceId,
      OffsetDateTime requestedStart,
      String name,
      String phone,
      UUID operationId) {
    agendaLock.acquire(tenantId);
    gate.protectPublicationUntilCommit(slug, tenantId);
    gate.requireVisibleTenant(slug, tenantId);
    ServiceAvailabilityView service = requireActiveService(tenantId, serviceId);
    Optional<ValidatedAppointmentSlot> currentSlot =
        slots.findCurrentSlot(tenantId, serviceId, requestedStart);
    ValidatedAppointmentSlot slot = currentSlot.orElseThrow(SlotNoLongerAvailableException::new);
    UUID clientId = clients.findOrCreate(tenantId, name, phone);
    UUID appointmentId = operationId;
    var start = slot.startsAt().toInstant();
    var serviceEnd = slot.serviceEndsAt().toInstant();
    var occupiedUntil = serviceEnd.plusSeconds(service.bufferAfterMinutes() * 60L);
    Appointment appointment =
        new Appointment(
            appointmentId,
            tenantId,
            clientId,
            serviceId,
            start,
            serviceEnd,
            occupiedUntil,
            slot.timeZone(),
            name,
            service.name(),
            service.durationMinutes(),
            service.bufferAfterMinutes(),
            null,
            null,
            AppointmentStatus.CONFIRMED,
            clock.instant(),
            1);
    String traceId = correlation.currentTraceId().toString();
    appointments.insertConfirmed(appointment, operationId.toString(), traceId);
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            tenantId,
            null,
            "SYSTEM",
            "PUBLIC_BOOKING",
            "APPOINTMENT_CONFIRMED",
            "APPOINTMENT",
            appointmentId,
            "COMMITTED",
            UUID.fromString(traceId),
            UUID.fromString(traceId),
            "{\"changed_fields\":[\"appointment\"],\"version_before\":0,\"version_after\":1}"));
    outbox.record(
        new OutboxEvent(
            UUID.randomUUID(),
            tenantId,
            "APPOINTMENT",
            appointmentId,
            1,
            "AppointmentConfirmed",
            1,
            operationId,
            UUID.fromString(traceId),
            "{\"appointmentId\":\"" + appointmentId + "\",\"version\":1}"));
    return new PublicAppointmentReceipt(
        appointmentId,
        operationId,
        AppointmentStatus.CONFIRMED.name(),
        service.name(),
        slot.startsAt(),
        slot.serviceEndsAt(),
        slot.timeZone());
  }

  private ServiceAvailabilityView requireActiveService(UUID tenantId, UUID serviceId) {
    ServiceAvailabilityView service =
        services
            .findForBooking(tenantId, serviceId)
            .orElseThrow(PublicAppointmentUnavailableException::new);
    if (!service.tenantId().equals(tenantId)
        || !service.serviceId().equals(serviceId)
        || !service.active()) {
      throw new PublicAppointmentUnavailableException();
    }
    return service;
  }
}
