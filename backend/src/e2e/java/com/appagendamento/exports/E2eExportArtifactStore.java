package com.appagendamento.exports;

import com.appagendamento.exports.application.ExportArtifactStore;
import com.appagendamento.exports.application.ExportStorageException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** In-memory artifact adapter copied into the explicitly isolated E2E image only. */
@Component
@Primary
@Profile("e2e")
public class E2eExportArtifactStore implements ExportArtifactStore {

  private final Map<String, StoredArtifact> objects = new ConcurrentHashMap<>();
  private final Map<String, Long> tombstones = new ConcurrentHashMap<>();
  private final Map<String, Object> objectLocks = new ConcurrentHashMap<>();

  @Override
  public boolean available() {
    return true;
  }

  @Override
  public void put(String objectKey, long fencingVersion, byte[] encryptedContent) {
    synchronized (objectLocks.computeIfAbsent(objectKey, ignored -> new Object())) {
      Long deletedAt = tombstones.get(objectKey);
      if (deletedAt != null && fencingVersion <= deletedAt) {
        throw new ExportStorageException(false);
      }
      objects.compute(
          objectKey,
          (key, current) -> {
          if (current != null && fencingVersion < current.fencingVersion()) {
            throw new ExportStorageException(false);
          }
          if (current != null
              && fencingVersion == current.fencingVersion()
              && !Arrays.equals(current.bytes(), encryptedContent)) {
            throw new ExportStorageException(false);
          }
          return current == null || fencingVersion > current.fencingVersion()
              ? new StoredArtifact(fencingVersion, encryptedContent.clone())
              : current;
          });
    }
  }

  @Override
  public byte[] get(String objectKey) {
    StoredArtifact artifact = objects.get(objectKey);
    if (artifact == null) throw new ExportStorageException(false);
    return artifact.bytes().clone();
  }

  @Override
  public void delete(String objectKey, long fencingVersion) {
    synchronized (objectLocks.computeIfAbsent(objectKey, ignored -> new Object())) {
      Long previous = tombstones.get(objectKey);
      if (previous != null && fencingVersion < previous) {
        throw new ExportStorageException(false);
      }
      StoredArtifact current = objects.get(objectKey);
      if (current != null && fencingVersion < current.fencingVersion()) {
        throw new ExportStorageException(false);
      }
      objects.computeIfPresent(
          objectKey,
          (ignored, artifact) -> artifact.fencingVersion() <= fencingVersion ? null : artifact);
      tombstones.merge(objectKey, fencingVersion, Math::max);
    }
  }

  private record StoredArtifact(long fencingVersion, byte[] bytes) {}
}
