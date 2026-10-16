package com.appagendamento.publicpage.api;

import com.appagendamento.publicpage.domain.PublicBusiness;

public record PublicBusinessResponse(
    String name, String slug, String timeZone, String contactPhone) {

  public static PublicBusinessResponse from(PublicBusiness business) {
    return new PublicBusinessResponse(
        business.name(), business.slug(), business.timeZone(), business.contactPhone());
  }
}
