package com.appagendamento.exports.application;

import com.appagendamento.exports.domain.ExportAuthorizationDeniedException;
import com.appagendamento.exports.domain.ExportJobView;
import com.appagendamento.exports.domain.ExportNotFoundException;
import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import java.time.Clock;
import org.springframework.stereotype.Service;

@Service
public class GetExport {

  private final AdminAccessPolicy accessPolicy;
  private final ExportRepository repository;
  private final Clock clock;

  public GetExport(AdminAccessPolicy accessPolicy, ExportRepository repository, Clock clock) {
    this.accessPolicy = accessPolicy;
    this.repository = repository;
    this.clock = clock;
  }

  public ExportJobView execute(AuthenticatedAdmin actor, java.util.UUID exportId) {
    requireCurrent(actor);
    return repository.find(actor.tenantId(), exportId).orElseThrow(ExportNotFoundException::new);
  }

  public java.util.Optional<ExportJobView> active(AuthenticatedAdmin actor) {
    requireCurrent(actor);
    return repository.findActive(actor.tenantId(), clock.instant());
  }

  private void requireCurrent(AuthenticatedAdmin actor) {
    if (actor == null || !accessPolicy.isCurrent(actor)) {
      throw new ExportAuthorizationDeniedException();
    }
  }
}
