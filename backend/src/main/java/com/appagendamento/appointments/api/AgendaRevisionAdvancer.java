package com.appagendamento.appointments.api;

import java.util.UUID;

public interface AgendaRevisionAdvancer {

  long advance(UUID tenantId);
}
