package com.appagendamento.publicpage.application;

import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.publicpage.domain.PublicBusiness;
import com.appagendamento.publicpage.domain.PublicationState;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GetPublicationStatus {

  private final AdminAccessPolicy accessPolicy;
  private final PublicationRepository publications;
  private final EvaluatePublicationReadiness readiness;

  public GetPublicationStatus(
      AdminAccessPolicy accessPolicy,
      PublicationRepository publications,
      EvaluatePublicationReadiness readiness) {
    this.accessPolicy = accessPolicy;
    this.publications = publications;
    this.readiness = readiness;
  }

  @Transactional(readOnly = true)
  public AdminPublicationStatus execute(AuthenticatedAdmin actor) {
    UUID tenantId = actor.tenantId();
    if (!accessPolicy.isCurrent(actor)) {
      throw new PublicationNotFoundException();
    }
    PublicBusiness business =
        publications
            .findBusinessByTenantId(tenantId)
            .orElseThrow(PublicationNotFoundException::new);
    PublicationState state =
        publications.findState(tenantId).orElseThrow(PublicationNotFoundException::new);
    return new AdminPublicationStatus(
        business.name(), business.slug(), business.timeZone(), state, readiness.execute(tenantId));
  }

  public record AdminPublicationStatus(
      String businessName,
      String slug,
      String timeZone,
      PublicationState state,
      PublicationReadiness readiness) {

    public AdminPublicationStatus withState(PublicationState commandState) {
      return new AdminPublicationStatus(businessName, slug, timeZone, commandState, readiness);
    }
  }
}
