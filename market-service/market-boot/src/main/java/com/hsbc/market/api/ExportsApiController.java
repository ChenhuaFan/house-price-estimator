package com.hsbc.market.api;

import com.hsbc.market.application.port.in.ExportUseCase;
import com.hsbc.market.application.port.out.ExportStorage;
import com.hsbc.market.contract.api.ExportsApi;
import com.hsbc.market.contract.model.ExportCreateRequest;
import com.hsbc.market.contract.model.ExportJob;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ExportsApiController implements ExportsApi {
  private final ExportUseCase useCase;
  private final ExportStorage storage;
  private final ApiModelMapper mapper;

  public ExportsApiController(ExportUseCase useCase, ExportStorage storage, ApiModelMapper mapper) {
    this.useCase = useCase;
    this.storage = storage;
    this.mapper = mapper;
  }

  @Override
  public ResponseEntity<ExportJob> createExport(
      UUID idempotencyKey, ExportCreateRequest request, UUID correlationId) {
    ExportJob job = mapper.toContract(useCase.create(mapper.toDomain(idempotencyKey, request)));
    return ResponseEntity.accepted()
        .header(HttpHeaders.LOCATION, "/api/v2/market/exports/" + job.getExportId())
        .header(HttpHeaders.RETRY_AFTER, "2")
        .body(job);
  }

  @Override
  public ResponseEntity<ExportJob> getExport(UUID exportId, UUID correlationId) {
    return ResponseEntity.ok(mapper.toContract(useCase.get(exportId)));
  }

  @Override
  public ResponseEntity<Void> cancelExport(UUID exportId, UUID correlationId) {
    useCase.cancel(exportId);
    return ResponseEntity.accepted().build();
  }

  @Override
  public ResponseEntity<Resource> downloadExport(UUID exportId, UUID correlationId) {
    var content = useCase.locateContent(exportId);
    Resource resource = new FileSystemResource(storage.path(content.contentKey()));
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
