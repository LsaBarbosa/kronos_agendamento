package com.appagendamento.appointments.api;

import java.util.UUID;

public interface TenantAgendaLock {

  void acquire(UUID tenantId);
}
