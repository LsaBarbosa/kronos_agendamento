package com.appagendamento.exports.infrastructure;

import com.appagendamento.exports.application.ExportArtifactStore;
import com.appagendamento.exports.application.ExportStorageException;
import org.springframework.stereotype.Component;

@Component
public class UnavailableExportArtifactStore implements ExportArtifactStore {

  @Override
  public boolean available() {
    return false;
  }

  @Override
  public void put(String objectKey, long fencingVersion, byte[] encryptedContent) {
    throw new ExportStorageException(false);
  }

  @Override
  public byte[] get(String objectKey) {
    throw new ExportStorageException(false);
  }

  @Override
  public void delete(String objectKey, long fencingVersion) {
    throw new ExportStorageException(false);
  }
}
