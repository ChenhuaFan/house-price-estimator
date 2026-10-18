package com.hsbc.market.export.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalExportStorageTest {
  @TempDir Path directory;

  @Test
  void createsCommitsReadsAndDeletesArtifactsWithinItsRoot() throws Exception {
    LocalExportStorage storage = new LocalExportStorage(directory);
    UUID exportId = UUID.randomUUID();
    LocalExportStorage.TempFile temporary = storage.createTemp(exportId);
    temporary.output().write("content".getBytes(StandardCharsets.UTF_8));
    temporary.output().close();

    String key = storage.commit(exportId, temporary.path(), "csv");
    assertThat(key).isEqualTo(exportId + "/property-market-analysis.csv");
    assertThat(storage.path(key)).isRegularFile();
    assertThat(new String(storage.open(key).readAllBytes(), StandardCharsets.UTF_8))
        .isEqualTo("content");

    storage.delete(key);
    assertThat(storage.path(key)).doesNotExist();
    storage.delete(null);
  }

  @Test
  void rejectsMissingTraversalAndForeignTemporaryPaths() throws Exception {
    LocalExportStorage storage = new LocalExportStorage(directory);
    assertThatThrownBy(() -> storage.path(null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> storage.path("  ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> storage.path("../escape"))
        .isInstanceOf(IllegalArgumentException.class);

    Path foreign = Files.createTempFile("foreign-export", ".part");
    try {
      assertThatThrownBy(() -> storage.deleteTemp(foreign))
          .isInstanceOf(IOException.class)
          .hasMessageContaining("escaped");
    } finally {
      Files.deleteIfExists(foreign);
    }

    LocalExportStorage.TempFile own = storage.createTemp(UUID.randomUUID());
    own.output().close();
    storage.deleteTemp(own.path());
    assertThat(own.path()).doesNotExist();
  }
}
