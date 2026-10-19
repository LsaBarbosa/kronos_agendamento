package com.appagendamento.appointments.application;

import com.appagendamento.appointments.domain.ClientAppointmentAccessException;
import com.appagendamento.appointments.domain.ClientAppointmentNotFoundException;
import com.appagendamento.appointments.domain.ClientAppointmentQueryException;
import com.appagendamento.clientaccess.api.ClientAccessIdentityQuery;
import com.appagendamento.clientaccess.api.VerifiedClientIdentity;
import com.appagendamento.clients.api.ClientAccountLookup;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GetClientAppointments {

  private static final int PAGE_SIZE = 20;

  private final ClientAccessIdentityQuery identities;
  private final ClientAccountLookup clients;
  private final AppointmentRepository appointments;
  private final Clock clock;

  public GetClientAppointments(
      ClientAccessIdentityQuery identities,
      ClientAccountLookup clients,
      AppointmentRepository appointments,
      Clock clock) {
    this.identities = identities;
    this.clients = clients;
    this.appointments = appointments;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public ClientAppointmentPage list(String rawCookieHeader, String status, String cursor) {
    VerifiedClientIdentity identity = requireIdentity(rawCookieHeader);
    if (status != null && !status.equals("future")) {
      throw new ClientAppointmentQueryException();
    }
    UUID clientId = resolveClient(identity);
    boolean futureOnly = "future".equals(status);
    Cursor position = Cursor.decode(cursor, identity.tenantId(), clientId, appointments);
    Instant now = clock.instant();
    List<ClientAppointmentView> fetched =
        appointments.findClientAppointments(
            identity.tenantId(),
            clientId,
            futureOnly,
            now,
            position == null ? null : position.startsAt(),
            position == null ? null : position.appointmentId(),
            PAGE_SIZE + 1);
    boolean hasMore = fetched.size() > PAGE_SIZE;
    List<ClientAppointmentView> items =
        List.copyOf(fetched.subList(0, Math.min(PAGE_SIZE, fetched.size())));
    String nextCursor = hasMore && !items.isEmpty() ? Cursor.encode(items.getLast()) : null;
    return new ClientAppointmentPage(items, nextCursor, hasMore);
  }

  @Transactional(readOnly = true)
  public ClientAppointmentView detail(String rawCookieHeader, UUID appointmentId) {
    VerifiedClientIdentity identity = requireIdentity(rawCookieHeader);
    UUID clientId = resolveClient(identity);
    return appointments
        .findClientAppointment(identity.tenantId(), clientId, appointmentId)
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

  private record Cursor(Instant startsAt, UUID appointmentId) {

    static Cursor decode(
        String encoded, UUID tenantId, UUID clientId, AppointmentRepository appointments) {
      if (encoded == null || encoded.isBlank()) return null;
      try {
        if (encoded.length() > 64) throw new IllegalArgumentException();
        UUID appointmentId = UUID.fromString(encoded);
        ClientAppointmentView ownerBoundPosition =
            appointments
                .findClientAppointment(tenantId, clientId, appointmentId)
                .orElseThrow(IllegalArgumentException::new);
        return new Cursor(ownerBoundPosition.startsAt(), appointmentId);
      } catch (RuntimeException exception) {
        throw new ClientAppointmentQueryException();
      }
    }

    static String encode(ClientAppointmentView appointment) {
      return appointment.appointmentId().toString();
    }
  }
}
