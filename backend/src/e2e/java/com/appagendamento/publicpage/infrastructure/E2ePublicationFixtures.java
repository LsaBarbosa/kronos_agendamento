package com.appagendamento.publicpage.infrastructure;

import com.appagendamento.availability.application.AppointmentOccupancyQuery;
import com.appagendamento.availability.domain.OccupiedInterval;
import com.appagendamento.publicpage.application.PublicationEvidence;
import com.appagendamento.publicpage.domain.PublicationBlocker;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("e2e")
public class E2ePublicationFixtures implements PublicationEvidence, AppointmentOccupancyQuery {

  @Override
  public List<PublicationBlocker> unavailableEvidence(UUID tenantId) {
    return List.of();
  }

  @Override
  public List<OccupiedInterval> findOverlapping(UUID tenantId, Instant startsAt, Instant endsAt) {
    return List.of();
  }
}
