package com.appagendamento.publicpage.application;

import com.appagendamento.publicpage.domain.PublicBusiness;
import org.springframework.stereotype.Service;

@Service
public class GetPublicBusiness {

  private final PublicPageAccess access;

  public GetPublicBusiness(PublicPageAccess access) {
    this.access = access;
  }

  public PublicBusiness execute(String slug) {
    return access.requireVisible(slug);
  }
}
