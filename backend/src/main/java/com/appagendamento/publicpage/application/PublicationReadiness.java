package com.appagendamento.publicpage.application;

import com.appagendamento.publicpage.domain.PublicationBlocker;
import java.util.List;

public record PublicationReadiness(boolean ready, List<PublicationBlocker> blockers) {

  public PublicationReadiness {
    blockers = List.copyOf(blockers);
    if (ready != blockers.isEmpty()) {
      throw new IllegalArgumentException("Estado de prontidão inconsistente");
    }
  }
}
