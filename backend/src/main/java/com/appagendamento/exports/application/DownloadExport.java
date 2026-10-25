package com.appagendamento.exports.application;

import com.appagendamento.exports.domain.ExportUnavailableException;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class DownloadExport {

  private final AuthorizeExportDownload authorize;
  private final RecordExportDownload record;
  private final ExportArtifactStore store;
  private final ExportCryptography cryptography;

  public DownloadExport(
      AuthorizeExportDownload authorize,
      RecordExportDownload record,
      ExportArtifactStore store,
      ExportCryptography cryptography) {
    this.authorize = authorize;
    this.record = record;
    this.store = store;
    this.cryptography = cryptography;
  }

  public ExportContent execute(AuthenticatedAdmin actor, UUID exportId, String handle) {
    var authorized = authorize.execute(actor, exportId, handle);
    var artifact = authorized.artifact();
    if (!store.available() || !cryptography.available()) throw new ExportUnavailableException();
    byte[] encrypted;
    try {
      encrypted = store.get(artifact.artifactKey());
    } catch (ExportStorageException failure) {
      throw new ExportUnavailableException();
    }
    if (!sha256(encrypted).equals(artifact.artifactDigest())
        || encrypted.length != artifact.artifactSize()) {
      throw new ExportUnavailableException();
    }
    byte[] content =
        cryptography.decrypt(
            encrypted,
            artifact.encryptionKeyId(),
            "EXPORT_ARTIFACT",
            actor.tenantId().toString(),
            exportId.toString());
    record.execute(actor, exportId, authorized.handleDigest());
    return new ExportContent(content, artifact.format(), sha256(content));
  }

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
