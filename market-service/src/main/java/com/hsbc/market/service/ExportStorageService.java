package com.hsbc.market.service;

import com.hsbc.market.export.storage.LocalExportStorage;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/** Adapts stored export content to Spring resources for download responses. */
@Service
public class ExportStorageService {
  private final LocalExportStorage storage;

  public ExportStorageService(LocalExportStorage storage) {
    this.storage = storage;
  }

  /** Returns a file resource for a previously validated storage key. */
  public Resource asResource(String contentKey) {
    return new FileSystemResource(storage.path(contentKey));
  }
}
