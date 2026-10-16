package com.appagendamento.publicpage.infrastructure;

import com.appagendamento.publicpage.application.PublicationEvidence;
import com.appagendamento.publicpage.domain.PublicationBlocker;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("e2e")
public class E2ePublicationFixtures implements PublicationEvidence {

  @Override
  public List<PublicationBlocker> unavailableEvidence(UUID tenantId) {
    return List.of();
  }
}
