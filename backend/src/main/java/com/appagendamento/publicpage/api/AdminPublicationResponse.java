package com.appagendamento.publicpage.api;

import com.appagendamento.publicpage.application.GetPublicationStatus.AdminPublicationStatus;
import com.appagendamento.publicpage.domain.PublicationBlocker;
import java.util.List;

public record AdminPublicationResponse(
    String businessName,
    String slug,
    String timeZone,
    String contactPhone,
    boolean published,
    long version,
    boolean ready,
    List<PublicationBlocker> blockers) {

  public static AdminPublicationResponse from(AdminPublicationStatus status) {
    return new AdminPublicationResponse(
        status.businessName(),
        status.slug(),
        status.timeZone(),
        status.state().contactPhone(),
        status.state().published(),
        status.state().version(),
        status.readiness().ready(),
        status.readiness().blockers());
  }
}
