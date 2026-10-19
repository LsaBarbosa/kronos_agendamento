package com.appagendamento.appointments.application;

import com.appagendamento.appointments.domain.ClientAppointmentAccessException;
import com.appagendamento.appointments.domain.ClientAppointmentNotFoundException;
import com.appagendamento.appointments.domain.ClientAppointmentQueryException;
import com.appagendamento.clientaccess.api.ClientAccessIdentityQuery;
import com.appagendamento.clientaccess.api.VerifiedClientIdentity;
import com.appagendamento.clients.api.ClientAccountLookup;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
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
    String nextCursor =
        hasMore && !items.isEmpty()
            ? Cursor.encode(items.getLast(), identity.tenantId(), clientId)
            : null;
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
        if (encoded.length() > 256) throw new IllegalArgumentException();
        String decoded = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        String[] fields = decoded.split("\\|", -1);
        if (fields.length != 3) throw new IllegalArgumentException();
        Instant startsAt = Instant.parse(fields[0]);
        UUID appointmentId = UUID.fromString(fields[1]);
        String binding = fields[2];
        if (!binding.equals(tenantId + ":" + clientId)) throw new IllegalArgumentException();
        appointments
            .findClientAppointment(tenantId, clientId, appointmentId)
            .orElseThrow(IllegalArgumentException::new);
        return new Cursor(startsAt, appointmentId);
      } catch (RuntimeException exception) {
        throw new ClientAppointmentQueryException();
      }
    }

    static String encode(ClientAppointmentView appointment, UUID tenantId, UUID clientId) {
      String value =
          appointment.startsAt()
              + "|"
              + appointment.appointmentId()
              + "|"
              + tenantId
              + ":"
              + clientId;
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
  }
}
