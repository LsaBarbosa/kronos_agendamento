package com.appagendamento.publicpage.application;

import com.appagendamento.publicpage.domain.PublicationBlocker;
import java.util.List;

public class PublicationNotReadyException extends RuntimeException {

  private final List<PublicationBlocker> blockers;

  public PublicationNotReadyException(List<PublicationBlocker> blockers) {
    this.blockers = List.copyOf(blockers);
  }

  public List<PublicationBlocker> blockers() {
    return blockers;
  }
}
