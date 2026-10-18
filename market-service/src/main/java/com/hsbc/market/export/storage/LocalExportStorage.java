package com.hsbc.market.export.storage;

import com.hsbc.market.config.MarketProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Stores export artifacts under a normalized local root with traversal protection. */
@Component
public class LocalExportStorage {
  public record TempFile(Path path, java.io.OutputStream output) {}

  private final Path root;
  private final Path parts;

  @Autowired
  public LocalExportStorage(MarketProperties properties) {
    this(properties.export().root());
  }

  public LocalExportStorage(Path root) {
    try {
      this.root = root.toAbsolutePath().normalize();
      this.parts = this.root.resolve(".parts");
      Files.createDirectories(this.parts);
      if (!Files.isWritable(this.parts)) {
        throw new IllegalStateException("export storage is not writable");
      }
    } catch (IOException error) {
      throw new IllegalStateException("cannot initialize export storage", error);
    }
  }

  public TempFile createTemp(UUID exportId) throws IOException {
    Path path = Files.createTempFile(parts, exportId + "-", ".part");
    return new TempFile(
        path,
        Files.newOutputStream(
            path, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));
  }

  /** Atomically promotes a completed temporary file into its final export location. */
  public String commit(UUID exportId, Path tempPath, String extension) throws IOException {
    Path directory = root.resolve(exportId.toString()).normalize();
    requireUnderRoot(directory);
    Files.createDirectories(directory);
    Path finalPath = directory.resolve("property-market-analysis." + extension).normalize();
    requireUnderRoot(finalPath);
    try (FileChannel channel = FileChannel.open(tempPath, StandardOpenOption.WRITE)) {
      channel.force(true);
    }
    try {
      Files.move(tempPath, finalPath, StandardCopyOption.ATOMIC_MOVE);
    } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
      Files.move(tempPath, finalPath);
    }
    return root.relativize(finalPath).toString().replace('\\', '/');
  }

  public InputStream open(String contentKey) throws IOException {
    return Files.newInputStream(resolve(contentKey));
  }

  public Path path(String contentKey) {
    return resolve(contentKey);
  }

  public void delete(String contentKey) throws IOException {
    if (contentKey == null) return;
    Path file = resolve(contentKey);
    Files.deleteIfExists(file);
    Path parent = file.getParent();
    if (parent != null && !parent.equals(root)) Files.deleteIfExists(parent);
  }

  public void deleteTemp(Path path) throws IOException {
    Path normalized = path.toAbsolutePath().normalize();
    if (!normalized.startsWith(parts))
      throw new IOException("temporary path escaped export storage");
    Files.deleteIfExists(normalized);
  }

  private Path resolve(String contentKey) {
    if (contentKey == null || contentKey.isBlank()) {
      throw new IllegalArgumentException("content key is missing");
    }
    Path resolved = root.resolve(contentKey).normalize();
    requireUnderRoot(resolved);
    return resolved;
  }

  private void requireUnderRoot(Path path) {
    if (!path.startsWith(root))
      throw new IllegalArgumentException("content key escaped export storage");
  }
}
