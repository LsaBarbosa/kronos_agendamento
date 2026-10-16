package com.appagendamento.publicpage.application;

import com.appagendamento.publicpage.domain.PublicationBlocker;
import java.util.List;
import java.util.UUID;

public interface PublicationEvidence {

  List<PublicationBlocker> unavailableEvidence(UUID tenantId);
}
