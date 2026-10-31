package com.appagendamento.exports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.appagendamento.exports.infrastructure.E2eFileExportArtifactStore;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class E2eFileExportArtifactStoreTest {

  @TempDir Path root;

  @Test
  void persistsEncryptedBytesIdempotentlyAndFencesWritesAfterTombstone() {
    var store = new E2eFileExportArtifactStore(root.toString());
    String objectKey = UUID.randomUUID() + "/exports/" + UUID.randomUUID();
    byte[] ciphertext = new byte[] {0, 17, -5, 32};

    assertThat(store.available()).isTrue();
    store.put(objectKey, 3, ciphertext);
    store.put(objectKey, 3, ciphertext);
    assertThat(store.get(objectKey)).containsExactly(ciphertext);
    assertThatThrownBy(() -> store.put(objectKey, 2, ciphertext))
        .isInstanceOf(com.appagendamento.exports.application.ExportStorageException.class);

    store.delete(objectKey, 4);
    store.delete(objectKey, 4);
    assertThatThrownBy(() -> store.get(objectKey))
        .isInstanceOf(com.appagendamento.exports.application.ExportStorageException.class);
    assertThatThrownBy(() -> store.put(objectKey, 4, ciphertext))
        .isInstanceOf(com.appagendamento.exports.application.ExportStorageException.class);
    assertThatThrownBy(() -> store.put(objectKey, 3, ciphertext))
        .isInstanceOf(com.appagendamento.exports.application.ExportStorageException.class);
  }
}
