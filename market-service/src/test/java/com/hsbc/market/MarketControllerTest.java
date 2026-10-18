package com.hsbc.market;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class MarketControllerTest {
    private static final String CORRELATION_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

    @Mock
    private MarketService service;

    private MockMvc mvc;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        mvc = MockMvcBuilders.standaloneSetup(new MarketController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new CorrelationFilter(mapper))
                .build();
    }

    @Test
    void livenessCreatesAndPreservesCorrelationIds() throws Exception {
        mvc.perform(get("/health/live").header("X-Correlation-ID", CORRELATION_ID))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-ID", CORRELATION_ID))
                .andExpect(jsonPath("$.status").value("ok"));

        mvc.perform(get("/health/live"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Correlation-ID"));
    }

    @Test
    void invalidCorrelationIdStopsBeforeController() throws Exception {
        mvc.perform(get("/api/v1/market/analysis")
                        .header("X-Correlation-ID", "invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_CORRELATION_ID"));

        verify(service, never()).resolveVersion(any());
    }

    @Test
    void analysisBindsFiltersAndReturnsTheServiceResult() throws Exception {
        when(service.resolveVersion(MarketFixtures.DATASET_ID)).thenReturn(MarketFixtures.version());
        when(service.analysis(eq(MarketFixtures.version()), any())).thenReturn(MarketFixtures.analysis());

        mvc.perform(get("/api/v1/market/analysis")
                        .param("datasetVersionId", MarketFixtures.DATASET_ID.toString())
                        .param("priceMin", "200000.00")
                        .param("sort", "price")
                        .param("direction", "desc")
                        .header("X-Correlation-ID", CORRELATION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datasetVersionId")
                        .value(MarketFixtures.DATASET_ID.toString()))
                .andExpect(jsonPath("$.matchingCount").value(2))
                .andExpect(jsonPath("$.records[0].price").value(250000.00));
    }

    @Test
    void invalidQueryReturnsStructuredValidationError() throws Exception {
        mvc.perform(get("/api/v1/market/analysis")
                        .param("priceMin", "20")
                        .param("priceMax", "10")
                        .header("X-Correlation-ID", CORRELATION_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.correlation_id").value(CORRELATION_ID));
    }

    @Test
    void whatIfValidatesBodyAndMapsDependencyErrors() throws Exception {
        when(service.whatIf(any(), eq(CORRELATION_ID))).thenReturn(
                new MarketModels.WhatIfResponse(
                        MarketFixtures.input(), MarketFixtures.prediction()));
        String valid = mapper.writeValueAsString(MarketFixtures.input());

        mvc.perform(post("/api/v1/market/what-if")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(valid)
                        .header("X-Correlation-ID", CORRELATION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prediction.predicted_price").value("250000.00"));

        String invalid = valid.replace("\"bathrooms\":2.5", "\"bathrooms\":0.1");
        mvc.perform(post("/api/v1/market/what-if")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalid)
                        .header("X-Correlation-ID", CORRELATION_ID))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        when(service.whatIf(any(), eq(CORRELATION_ID)))
                .thenThrow(new ModelServiceException("down"));
        mvc.perform(post("/api/v1/market/what-if")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(valid)
                        .header("X-Correlation-ID", CORRELATION_ID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("MODEL_SERVICE_UNAVAILABLE"));
    }

    @Test
    void downstreamStructuredRejectionIsPreserved() throws Exception {
        when(service.whatIf(any(), eq(CORRELATION_ID))).thenThrow(
                new ModelServiceRejectedException(
                        422, "MODEL_OUTPUT_OUT_OF_DOMAIN", "bad output"));

        mvc.perform(post("/api/v1/market/what-if")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(MarketFixtures.input()))
                        .header("X-Correlation-ID", CORRELATION_ID))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("MODEL_OUTPUT_OUT_OF_DOMAIN"))
                .andExpect(jsonPath("$.error.message").value("bad output"));
    }

    @Test
    void exportsUseExpectedMediaTypesAndDisposition() throws Exception {
        when(service.resolveVersion(MarketFixtures.DATASET_ID)).thenReturn(MarketFixtures.version());
        when(service.csv(eq(MarketFixtures.version()), any())).thenReturn("a,b\n".getBytes(StandardCharsets.UTF_8));
        when(service.pdf(eq(MarketFixtures.version()), any(), any())).thenReturn("%PDF-".getBytes(StandardCharsets.US_ASCII));

        mvc.perform(get("/api/v1/market/exports/data.csv")
                        .param("datasetVersionId", MarketFixtures.DATASET_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"property-market-analysis.csv\""));

        mvc.perform(post("/api/v1/market/exports/report.pdf")
                        .param("datasetVersionId", MarketFixtures.DATASET_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("null"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"property-market-analysis.pdf\""));
    }
}
