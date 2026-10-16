package com.appagendamento.publicpage.application;

import com.appagendamento.publicpage.domain.PublicBusiness;
import com.appagendamento.publicpage.domain.PublicPageUnavailableException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublicPageAccess {

  private final PublicationRepository publications;
  private final EvaluatePublicationReadiness readiness;

  public PublicPageAccess(
      PublicationRepository publications, EvaluatePublicationReadiness readiness) {
    this.publications = publications;
    this.readiness = readiness;
  }

  @Transactional(readOnly = true)
  public PublicBusiness requireVisible(String slug) {
    PublicBusiness business =
        publications.findBusinessBySlug(slug).orElseThrow(PublicPageUnavailableException::new);
    if (!business.published()
        || !"ACTIVE".equals(business.status())
        || !readiness.execute(business.tenantId()).ready()) {
      throw new PublicPageUnavailableException();
    }
    return business;
  }
}
