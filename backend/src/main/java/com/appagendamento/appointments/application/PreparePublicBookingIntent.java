package com.appagendamento.appointments.application;

import com.appagendamento.appointments.api.PublicBookingGate;
import com.appagendamento.appointments.domain.PublicBookingRecoveryUnavailableException;
import com.appagendamento.catalog.api.ServiceAvailabilityQuery;
import com.appagendamento.catalog.api.ServiceAvailabilityView;
import com.appagendamento.clients.api.PublicClientRegistry;
import com.appagendamento.idempotency.application.IdempotencyKey;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PreparePublicBookingIntent {

  static final String COMMAND_SCOPE = "appointments.public.create.v1";
  private static final Duration CAPABILITY_LIFETIME = Duration.ofDays(7);

  private final PublicBookingGate gate;
  private final ServiceAvailabilityQuery services;
  private final PublicClientRegistry clients;
  private final PublicAppointmentCommandDigest digests;
  private final PublicBookingIntentRegistry intents;
  private final Clock clock;

  public PreparePublicBookingIntent(
      PublicBookingGate gate,
      ServiceAvailabilityQuery services,
      PublicClientRegistry clients,
      PublicAppointmentCommandDigest digests,
      PublicBookingIntentRegistry intents,
      Clock clock) {
    this.gate = gate;
    this.services = services;
    this.clients = clients;
    this.digests = digests;
    this.intents = intents;
    this.clock = clock;
  }

  @Transactional
  public void execute(
      String slug,
      String key,
      String recoveryCapability,
      UUID serviceId,
      OffsetDateTime startsAt,
      String rawName,
      String rawPhone) {
    UUID tenantId = gate.resolveVisibleTenant(slug);
    gate.requireVisibleTenant(slug, tenantId);
    services
        .findForBooking(tenantId, serviceId)
        .filter(ServiceAvailabilityView::active)
        .orElseThrow(
            com.appagendamento.appointments.domain.PublicAppointmentUnavailableException::new);
    String name = clients.canonicalizeName(rawName);
    String phone = clients.normalizePhone(rawPhone);
    if (recoveryCapability == null || !recoveryCapability.matches("[A-Za-z0-9_-]{43}")) {
      throw new PublicBookingRecoveryUnavailableException();
    }
    var digest = digests.create(tenantId, slug, serviceId, startsAt, name, phone);
    var intent =
        new PublicBookingIntent(
            tenantId,
            COMMAND_SCOPE,
            IdempotencyKey.digest(key),
            IdempotencyKey.digest(recoveryCapability),
            digest.persistedValue(),
            clock.instant().plus(CAPABILITY_LIFETIME));
    PublicBookingIntent existing = intents.bind(intent);
    if (!digest.matches(existing.requestDigest())
        || !existing.recoveryDigest().equals(intent.recoveryDigest())) {
      throw new com.appagendamento.idempotency.application.IdempotencyKeyReusedException();
    }
    if (!existing.expiresAt().isAfter(clock.instant())) {
      throw new PublicBookingRecoveryUnavailableException();
    }
  }
}
