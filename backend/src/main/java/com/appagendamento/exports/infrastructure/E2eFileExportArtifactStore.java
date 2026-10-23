package com.appagendamento.exports.infrastructure;

import com.appagendamento.exports.application.ExportArtifactStore;
import com.appagendamento.exports.application.ExportStorageException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Ephemeral filesystem adapter available only in the isolated browser E2E profile. */
@Component
@Profile("e2e")
public class E2eFileExportArtifactStore implements ExportArtifactStore {

  private final Path root;

  public E2eFileExportArtifactStore(
      @Value("${app.exports.e2e-storage-root:/tmp/agendamento-e2e-exports}") String root) {
    this.root = Path.of(root).toAbsolutePath().normalize();
  }

  @Override
  public boolean available() {
    try {
      Files.createDirectories(root);
      return Files.isDirectory(root) && Files.isWritable(root);
    } catch (IOException failure) {
      return false;
    }
  }

  @Override
  public synchronized void put(String objectKey, long fencingVersion, byte[] encryptedContent) {
    Path object = path(objectKey);
    Path metadata = metadata(object);
    Fence prior = readFence(metadata);
    if (prior.deleted() || fencingVersion < prior.version()) {
      throw new ExportStorageException(false);
    }
    try {
      Files.createDirectories(object.getParent());
      if (fencingVersion == prior.version() && Files.exists(object)) {
        if (java.security.MessageDigest.isEqual(Files.readAllBytes(object), encryptedContent))
          return;
        throw new ExportStorageException(false);
      }
      writeAtomic(metadata, fencingVersion + "\nfalse\n");
      writeAtomic(object, encryptedContent);
    } catch (IOException failure) {
      throw new ExportStorageException(true);
    }
  }

  @Override
  public synchronized byte[] get(String objectKey) {
    Path object = path(objectKey);
    Fence fence = readFence(metadata(object));
    if (fence.deleted() || !Files.isRegularFile(object)) throw new ExportStorageException(false);
    try {
      return Files.readAllBytes(object);
    } catch (IOException failure) {
      throw new ExportStorageException(true);
    }
  }

  @Override
  public synchronized void delete(String objectKey, long fencingVersion) {
    Path object = path(objectKey);
    Path metadata = metadata(object);
    Fence prior = readFence(metadata);
    if (fencingVersion < prior.version()) throw new ExportStorageException(false);
    try {
      Files.createDirectories(object.getParent());
      writeAtomic(metadata, fencingVersion + "\ntrue\n");
      Files.deleteIfExists(object);
    } catch (IOException failure) {
      throw new ExportStorageException(true);
    }
  }

  private Path path(String objectKey) {
    String[] parts = objectKey.split("/", -1);
    if (parts.length != 3
        || !parts[0].matches("[0-9a-fA-F-]{36}")
        || !parts[1].equals("exports")
        || !parts[2].matches("[0-9a-fA-F-]{36}")) {
      throw new ExportStorageException(false);
    }
    UUID.fromString(parts[0]);
    UUID.fromString(parts[2]);
    Path resolved = root.resolve(objectKey).normalize();
    if (!resolved.startsWith(root)) throw new ExportStorageException(false);
    return resolved;
  }

  private Path metadata(Path object) {
    return object.resolveSibling(object.getFileName() + ".fence");
  }

  private Fence readFence(Path metadata) {
    if (!Files.exists(metadata)) return new Fence(0, false);
    try {
      String[] values = Files.readString(metadata, StandardCharsets.US_ASCII).split("\\R");
      return new Fence(Long.parseLong(values[0]), Boolean.parseBoolean(values[1]));
    } catch (IOException | RuntimeException failure) {
      throw new ExportStorageException(false);
    }
  }

  private void writeAtomic(Path destination, String value) throws IOException {
    writeAtomic(destination, value.getBytes(StandardCharsets.US_ASCII));
  }

  private void writeAtomic(Path destination, byte[] value) throws IOException {
    Path temporary = Files.createTempFile(root, "export-e2e-", ".tmp");
    try {
      Files.write(temporary, value);
      try {
        Files.move(
            temporary,
            destination,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException unsupported) {
        Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private record Fence(long version, boolean deleted) {}
}
