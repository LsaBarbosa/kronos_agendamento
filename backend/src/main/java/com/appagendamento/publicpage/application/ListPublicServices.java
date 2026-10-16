package com.appagendamento.publicpage.application;

import com.appagendamento.publicpage.domain.PublicService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ListPublicServices {

  private final PublicPageAccess access;
  private final PublicationRepository publications;

  public ListPublicServices(PublicPageAccess access, PublicationRepository publications) {
    this.access = access;
    this.publications = publications;
  }

  @Transactional(readOnly = true)
  public List<PublicService> execute(String slug) {
    return publications.findActiveServices(access.requireVisible(slug).tenantId());
  }
}
