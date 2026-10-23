package com.appagendamento.exports.application;

import com.appagendamento.exports.domain.ExportAuthorizationDeniedException;
import com.appagendamento.exports.domain.ExportJobView;
import com.appagendamento.exports.domain.ExportNotFoundException;
import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import org.springframework.stereotype.Service;

@Service
public class GetExport {

  private final AdminAccessPolicy accessPolicy;
  private final ExportRepository repository;

  public GetExport(AdminAccessPolicy accessPolicy, ExportRepository repository) {
    this.accessPolicy = accessPolicy;
    this.repository = repository;
  }

  public ExportJobView execute(AuthenticatedAdmin actor, java.util.UUID exportId) {
    requireCurrent(actor);
    return repository.find(actor.tenantId(), exportId).orElseThrow(ExportNotFoundException::new);
  }

  private void requireCurrent(AuthenticatedAdmin actor) {
    if (actor == null || !accessPolicy.isCurrent(actor)) {
      throw new ExportAuthorizationDeniedException();
    }
  }
}
