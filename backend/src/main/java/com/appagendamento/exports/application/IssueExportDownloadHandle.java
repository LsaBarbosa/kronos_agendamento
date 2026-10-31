package com.appagendamento.exports.application;

import com.appagendamento.exports.domain.ExportAuthorizationDeniedException;
import com.appagendamento.exports.domain.ExportConflictException;
import com.appagendamento.exports.domain.ExportExpiredException;
import com.appagendamento.exports.domain.ExportNotFoundException;
import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IssueExportDownloadHandle {

  private final AdminAccessPolicy accessPolicy;
  private final ExportRepository repository;
  private final java.time.Clock clock;
  private final SecureRandom random = new SecureRandom();

  public IssueExportDownloadHandle(
      AdminAccessPolicy accessPolicy, ExportRepository repository, java.time.Clock clock) {
    this.accessPolicy = accessPolicy;
    this.repository = repository;
    this.clock = clock;
  }

  @Transactional
  public DownloadHandleResult execute(AuthenticatedAdmin actor, UUID exportId) {
    requireCurrent(actor);
    var job = repository.find(actor.tenantId(), exportId).orElseThrow(ExportNotFoundException::new);
    if (job.state() == com.appagendamento.exports.domain.ExportState.EXPIRED
        || job.expiresAt() != null && !clock.instant().isBefore(job.expiresAt())) {
      throw new ExportExpiredException();
    }
    if (job.state() != com.appagendamento.exports.domain.ExportState.AVAILABLE) {
      throw new ExportConflictException("EXPORT_NOT_AVAILABLE");
    }
    byte[] token = new byte[32];
    random.nextBytes(token);
    String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    String digest = digest(raw);
    var expiresAt =
        repository
            .issueHandle(actor.tenantId(), actor.adminUserId(), exportId, digest, clock.instant())
            .orElseThrow(ExportExpiredException::new);
    return new DownloadHandleResult(raw, expiresAt);
  }

  private void requireCurrent(AuthenticatedAdmin actor) {
    if (actor == null || !accessPolicy.isCurrent(actor)) {
      throw new ExportAuthorizationDeniedException();
    }
  }

  public static String digest(String raw) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
