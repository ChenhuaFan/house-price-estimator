package com.hsbc.market.controller.v1;

import com.hsbc.market.controller.RequestCorrelation;
import com.hsbc.market.model.v1.V1Models;
import com.hsbc.market.model.v1.V1QueryParameters;
import com.hsbc.market.service.V1CompatibilityService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Exposes legacy v1 endpoints while delegating behavior to current services. */
@Deprecated(forRemoval = true)
@RestController
public class V1CompatibilityController {
  private final V1CompatibilityService compatibilityService;
  private final RequestCorrelation correlation;

  public V1CompatibilityController(
      V1CompatibilityService compatibilityService, RequestCorrelation correlation) {
    this.compatibilityService = compatibilityService;
    this.correlation = correlation;
  }

  /** Reports process liveness for legacy deployments. */
  @GetMapping("/health/live")
  public Map<String, String> live() {
    return Map.of("status", "ok", "service", "market-service");
  }

  /** Returns the legacy dashboard analysis assembled by the compatibility service. */
  @GetMapping("/api/v1/market/analysis")
  public V1Models.MarketAnalysis analysis(@ModelAttribute V1QueryParameters parameters) {
    return compatibilityService.analysis(parameters);
  }

  /** Delegates a legacy what-if request to the current prediction workflow. */
  @PostMapping("/api/v1/market/what-if")
  public V1Models.WhatIfResponse whatIf(@Valid @RequestBody V1Models.PropertyInput input) {
    return compatibilityService.whatIf(input, correlation.current());
  }

  /** Streams the legacy CSV representation without buffering it in memory. */
  @GetMapping(value = "/api/v1/market/exports/data.csv", produces = "text/csv")
  public ResponseEntity<StreamingResponseBody> csv(@ModelAttribute V1QueryParameters parameters) {
    StreamingResponseBody body = output -> compatibilityService.writeCsv(parameters, output);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename("property-market-analysis.csv")
                .build()
                .toString())
        .body(body);
  }

  /** Streams the legacy PDF report with an optional prediction section. */
  @PostMapping(
      value = "/api/v1/market/exports/report.pdf",
      produces = MediaType.APPLICATION_PDF_VALUE)
  public ResponseEntity<StreamingResponseBody> pdf(
      @ModelAttribute V1QueryParameters parameters,
      @RequestBody(required = false) V1Models.WhatIfResponse prediction) {
    StreamingResponseBody body =
        output -> compatibilityService.writePdf(parameters, prediction, output);
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_PDF)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename("property-market-analysis.pdf")
                .build()
                .toString())
        .body(body);
  }
}
