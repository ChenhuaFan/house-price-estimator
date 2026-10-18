package com.hsbc.market;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hsbc.market.client.ModelServiceClient;
import com.hsbc.market.model.domain.MarketDomain.AdvisoryWarning;
import com.hsbc.market.model.domain.MarketDomain.Prediction;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "market.export.scheduling-enabled=false",
      "market.export.root=target/test-what-if-exports"
    })
@AutoConfigureMockMvc
class WhatIfApiIntegrationTest {
  private static final String REQUEST =
      """
      {"square_footage":1800,"bedrooms":3,"bathrooms":"2.5",
       "year_built":2005,"lot_size":6500,"distance_to_city_center":"4.125",
       "school_rating":"8.500"}
      """;

  @Autowired private MockMvc mvc;
  @MockitoBean private ModelServiceClient modelServiceClient;

  @Test
  void validAndAdvisoryPredictionsUseTheHttpContractAndCorrelationId() throws Exception {
    UUID correlationId = UUID.randomUUID();
    when(modelServiceClient.predict(any(), eq(correlationId)))
        .thenReturn(
            new Prediction(
                new BigDecimal("315000.00"),
                "USD",
                "model-v1",
                List.of(
                    new AdvisoryWarning(
                        "school_rating",
                        "8.500",
                        "1.000",
                        "8.000",
                        "value exceeds the advisory range"))));

    mvc.perform(
            post("/api/v2/market/what-if")
                .header("X-Correlation-ID", correlationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(REQUEST))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Correlation-ID", correlationId.toString()))
        .andExpect(jsonPath("$.inputs.square_footage").value(1800))
        .andExpect(jsonPath("$.prediction.predicted_price").value("315000.00"))
        .andExpect(jsonPath("$.prediction.model_version").value("model-v1"))
        .andExpect(jsonPath("$.prediction.warnings[0].field").value("school_rating"));
    verify(modelServiceClient).predict(any(), eq(correlationId));
  }

  @Test
  void invalidWhatIfIsRejectedBeforeTheModelClient() throws Exception {
    mvc.perform(
            post("/api/v2/market/what-if")
                .contentType(MediaType.APPLICATION_JSON)
                .content(REQUEST.replace("\"square_footage\":1800", "\"square_footage\":0")))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
  }

  @Test
  void whatIfResultsAreNotCachedAcrossModelVersions() throws Exception {
    UUID correlationId = UUID.randomUUID();
    when(modelServiceClient.predict(any(), eq(correlationId)))
        .thenReturn(
            new Prediction(new BigDecimal("310000.00"), "USD", "model-v1", List.of()),
            new Prediction(new BigDecimal("320000.00"), "USD", "model-v2", List.of()));

    mvc.perform(
            post("/api/v2/market/what-if")
                .header("X-Correlation-ID", correlationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(REQUEST))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.prediction.model_version").value("model-v1"));
    mvc.perform(
            post("/api/v2/market/what-if")
                .header("X-Correlation-ID", correlationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(REQUEST))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.prediction.model_version").value("model-v2"));

    verify(modelServiceClient, times(2)).predict(any(), eq(correlationId));
  }
}
