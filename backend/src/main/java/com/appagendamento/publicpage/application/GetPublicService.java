package com.appagendamento.publicpage.application;

import com.appagendamento.publicpage.domain.PublicService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GetPublicService {

  private final PublicPageAccess access;
  private final PublicationRepository publications;

  public GetPublicService(PublicPageAccess access, PublicationRepository publications) {
    this.access = access;
    this.publications = publications;
  }

  @Transactional(readOnly = true)
  public PublicService execute(String slug, UUID serviceId) {
    UUID tenantId = access.requireVisible(slug).tenantId();
    return publications
        .findActiveService(tenantId, serviceId)
        .orElseThrow(PublicationNotFoundException::new);
  }
}
