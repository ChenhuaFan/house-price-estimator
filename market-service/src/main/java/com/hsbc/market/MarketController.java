package com.hsbc.market;

import static com.hsbc.market.MarketModels.*;

import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping
public class MarketController {
    private final MarketService service;

    public MarketController(MarketService service) {
        this.service = service;
    }

    @GetMapping("/health/live")
    public Map<String, String> live() {
        return Map.of("status", "ok", "service", "market-service");
    }

    @GetMapping("/api/v1/market/analysis")
    public MarketAnalysis analysis(@ModelAttribute MarketQueryParameters parameters) {
        MarketQuery query = parameters.toQuery();
        DatasetVersion version = service.resolveVersion(query.datasetVersionId());
        return service.analysis(version, query);
    }

    @PostMapping("/api/v1/market/what-if")
    public WhatIfResponse whatIf(
            @Valid @RequestBody PropertyInput input, HttpServletRequest request) {
        return service.whatIf(input, correlationId(request));
    }

    @GetMapping(value = "/api/v1/market/exports/data.csv", produces = "text/csv")
    public ResponseEntity<byte[]> csv(@ModelAttribute MarketQueryParameters parameters) {
        MarketQuery query = parameters.toQuery();
        DatasetVersion version = service.resolveVersion(query.datasetVersionId());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("property-market-analysis.csv").build().toString())
                .body(service.csv(version, query));
    }

    @PostMapping(value = "/api/v1/market/exports/report.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(
            @ModelAttribute MarketQueryParameters parameters,
            @RequestBody(required = false) WhatIfResponse whatIf) {
        MarketQuery query = parameters.toQuery();
        DatasetVersion version = service.resolveVersion(query.datasetVersionId());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("property-market-analysis.pdf").build().toString())
                .body(service.pdf(version, query, whatIf));
    }

    private String correlationId(HttpServletRequest request) {
        return (String) request.getAttribute(CorrelationFilter.ATTRIBUTE);
    }
}
