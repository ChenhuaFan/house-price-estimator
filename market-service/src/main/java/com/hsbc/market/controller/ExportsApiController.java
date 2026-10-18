package com.hsbc.market.controller;

import com.hsbc.market.contract.api.ExportsApi;
import com.hsbc.market.contract.model.ExportCreateRequest;
import com.hsbc.market.contract.model.ExportJob;
import com.hsbc.market.service.ExportService;
import com.hsbc.market.service.ExportStorageService;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Implements v2 export lifecycle and download endpoints. */
@RestController
public class ExportsApiController implements ExportsApi {
  private final ExportService exportService;
  private final ExportStorageService exportStorageService;
  private final ApiModelMapper mapper;

  public ExportsApiController(
      ExportService exportService,
      ExportStorageService exportStorageService,
      ApiModelMapper mapper) {
    this.exportService = exportService;
    this.exportStorageService = exportStorageService;
    this.mapper = mapper;
  }

  /** Creates an asynchronous export job for an idempotent request. */
  @Override
  public ResponseEntity<ExportJob> createExport(
      UUID idempotencyKey, ExportCreateRequest request, UUID correlationId) {
    ExportJob job =
        mapper.toContract(exportService.create(mapper.toDomain(idempotencyKey, request)));

    return ResponseEntity.accepted()
        .header(HttpHeaders.LOCATION, "/api/v2/market/exports/" + job.getExportId())
        .header(HttpHeaders.RETRY_AFTER, "2")
        .body(job);
  }

  /** Returns the current state and metadata of an export job. */
  @Override
  public ResponseEntity<ExportJob> getExport(UUID exportId, UUID correlationId) {
    return ResponseEntity.ok(mapper.toContract(exportService.get(exportId)));
  }

  /** Requests cancellation of queued or running export work. */
  @Override
  public ResponseEntity<Void> cancelExport(UUID exportId, UUID correlationId) {
    exportService.cancel(exportId);

    return ResponseEntity.accepted().build();
  }

  /** Streams a completed artifact with integrity and download headers. */
  @Override
  public ResponseEntity<Resource> downloadExport(UUID exportId, UUID correlationId) {
    var content = exportService.locateContent(exportId);
    Resource resource = exportStorageService.asResource(content.contentKey());

    String digest =
        "sha-256=:"
            + Base64.getEncoder().encodeToString(HexFormat.of().parseHex(content.contentSha256()))
            + ':';

    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(content.contentType()))
        .contentLength(content.contentLength())
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(content.filename(), StandardCharsets.UTF_8)
                .build()
                .toString())
        .header("Content-Digest", digest)
        .eTag('"' + content.contentSha256() + '"')
        .cacheControl(CacheControl.noStore())
        .header("X-Content-Type-Options", "nosniff")
        .body(resource);
  }
}
