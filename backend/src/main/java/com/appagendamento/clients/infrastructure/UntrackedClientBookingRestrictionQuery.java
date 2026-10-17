package com.appagendamento.clients.infrastructure;

import com.appagendamento.clients.api.ClientBookingRestrictionQuery;
import com.appagendamento.clients.api.ClientBookingRestrictionStatus;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class UntrackedClientBookingRestrictionQuery implements ClientBookingRestrictionQuery {

  @Override
  public ClientBookingRestrictionStatus status(UUID tenantId, UUID clientId) {
    return ClientBookingRestrictionStatus.NOT_TRACKED;
  }
}
