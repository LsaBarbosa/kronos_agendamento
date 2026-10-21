package com.appagendamento.appointments.api;

import java.util.UUID;

public interface PublicBookingGate {

  UUID resolveVisibleTenant(String slug);

  void requireVisibleTenant(String slug, UUID expectedTenantId);

  void protectPublicationUntilCommit(String slug, UUID expectedTenantId);

  boolean allowsNewPublicAppointments(UUID tenantId);
}
