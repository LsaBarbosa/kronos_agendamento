package com.appagendamento.exports.application;

import com.appagendamento.exports.domain.ExportAuthorizationDeniedException;
import com.appagendamento.exports.domain.ExportConflictException;
import com.appagendamento.exports.domain.ExportExpiredException;
import com.appagendamento.exports.domain.ExportNotFoundException;
import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthorizeExportDownload {

  private final AdminAccessPolicy accessPolicy;
  private final ExportRepository repository;
  private final Clock clock;

  public AuthorizeExportDownload(
      AdminAccessPolicy accessPolicy, ExportRepository repository, Clock clock) {
    this.accessPolicy = accessPolicy;
    this.repository = repository;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public AuthorizedExportDownload execute(AuthenticatedAdmin actor, UUID exportId, String handle) {
    requireCurrent(actor);
    String digest = IssueExportDownloadHandle.digest(handle);
    var artifact =
        repository.authorizeDownload(
            actor.tenantId(), actor.adminUserId(), exportId, digest, clock.instant());
    if (artifact.isPresent()) return new AuthorizedExportDownload(artifact.get(), digest);
    var job = repository.find(actor.tenantId(), exportId).orElseThrow(ExportNotFoundException::new);
    if (job.state() == com.appagendamento.exports.domain.ExportState.EXPIRED
        || job.expiresAt() != null && !clock.instant().isBefore(job.expiresAt())) {
      throw new ExportExpiredException();
    }
    if (job.state() != com.appagendamento.exports.domain.ExportState.AVAILABLE) {
      throw new ExportConflictException("EXPORT_NOT_AVAILABLE");
    }
    throw new ExportConflictException("DOWNLOAD_HANDLE_INVALID");
  }

  private void requireCurrent(AuthenticatedAdmin actor) {
    if (actor == null || !accessPolicy.isCurrent(actor)) {
      throw new ExportAuthorizationDeniedException();
    }
  }
}
