package com.hsbc.market.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.market.exception.MarketException;
import com.hsbc.market.model.domain.MarketDomain;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ModelServiceClientTest {
  private MockRestServiceServer server;
  private ModelServiceClient adapter;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://model");
    server = MockRestServiceServer.bindTo(builder).build();
    adapter =
        new ModelServiceClient(
            builder.build(),
            new ObjectMapper().findAndRegisterModules(),
            1,
            new SimpleMeterRegistry());
  }

  @Test
  void usesCanonicalContractAndCorrelationHeader() {
    UUID correlation = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    server
        .expect(once(), requestTo("http://model/api/v1/predict"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("X-Correlation-ID", correlation.toString()))
        .andExpect(
            content()
                .json(
                    """
                        {"property":{"square_footage":1800,"bedrooms":3,"bathrooms":2.5,
                        "year_built":2005,"lot_size":6500,"distance_to_city_center":4.125,
                        "school_rating":8.500}}
                        """))
        .andRespond(
            withSuccess(
                """
                        {"predictions":[{"predicted_price":"250000.00","currency":"USD",
                        "model_version":"housing-linear-test","warnings":[]}]}
                        """,
                MediaType.APPLICATION_JSON));

    MarketDomain.Prediction prediction = adapter.predict(input(), correlation);

    assertThat(prediction.predictedPrice()).isEqualByComparingTo("250000.00");
    assertThat(prediction.modelVersion()).isEqualTo("housing-linear-test");
    server.verify();
  }

  @Test
  void preservesValidationCodeAndConvertsTransportFailure() {
    server
        .expect(requestTo("http://model/api/v1/predict"))
        .andRespond(
            withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    """
                                {"error":{"code":"MODEL_OUTPUT_OUT_OF_DOMAIN","message":"bad output"}}
                                """));

    assertThatThrownBy(() -> adapter.predict(input(), UUID.randomUUID()))
        .isInstanceOfSatisfying(
            MarketException.class,
            error -> assertThat(error.code()).isEqualTo("MODEL_OUTPUT_OUT_OF_DOMAIN"));

    RestClient.Builder retryBuilder = RestClient.builder().baseUrl("http://model-2");
    MockRestServiceServer retryServer = MockRestServiceServer.bindTo(retryBuilder).build();
    ModelServiceClient retryAdapter =
        new ModelServiceClient(
            retryBuilder.build(), new ObjectMapper(), 1, new SimpleMeterRegistry());
    retryServer
        .expect(requestTo("http://model-2/api/v1/predict"))
        .andRespond(withException(new IOException("connection failed")));
    assertThatThrownBy(() -> retryAdapter.predict(input(), UUID.randomUUID()))
        .isInstanceOfSatisfying(
            MarketException.class,
            error -> assertThat(error.code()).isEqualTo("MODEL_SERVICE_UNAVAILABLE"));
  }

  @Test
  void hidesDownstreamServerDetailsAndRejectsInvalidSuccessBodies() {
    server
        .expect(requestTo("http://model/api/v1/predict"))
        .andRespond(
            withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    """
                                {"error":{"code":"DB_FAILURE",
                                "message":"password=secret at /private/db.sql"}}
                                """));
    assertThatThrownBy(() -> adapter.predict(input(), UUID.randomUUID()))
        .isInstanceOfSatisfying(
            MarketException.class,
            error -> {
              assertThat(error.code()).isEqualTo("MODEL_SERVICE_UNAVAILABLE");
              assertThat(error.getMessage())
                  .isEqualTo("Prediction service is unavailable.")
                  .doesNotContain("secret", "/private");
            });

    RestClient.Builder invalidBuilder = RestClient.builder().baseUrl("http://invalid-model");
    MockRestServiceServer invalidServer = MockRestServiceServer.bindTo(invalidBuilder).build();
    ModelServiceClient invalidAdapter =
        new ModelServiceClient(
            invalidBuilder.build(), new ObjectMapper(), 1, new SimpleMeterRegistry());
    invalidServer
        .expect(requestTo("http://invalid-model/api/v1/predict"))
        .andRespond(withSuccess("{\"predictions\":[]}", MediaType.APPLICATION_JSON));
    assertThatThrownBy(() -> invalidAdapter.predict(input(), UUID.randomUUID()))
        .isInstanceOfSatisfying(
            MarketException.class,
            error -> assertThat(error.code()).isEqualTo("MODEL_SERVICE_UNAVAILABLE"));
  }

  private static MarketDomain.PropertyFeatures input() {
    return new MarketDomain.PropertyFeatures(
        1800,
        3,
        new BigDecimal("2.5"),
        2005,
        6500,
        new BigDecimal("4.125"),
        new BigDecimal("8.500"));
  }
}
