package com.hsbc.market;

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
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ModelServiceGatewayTest {
    private MockRestServiceServer server;
    private ModelServiceGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://model");
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new ModelServiceGateway(builder.build(), new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void predictionUsesCanonicalContractAndCorrelationHeader() {
        server.expect(once(), requestTo("http://model/api/v1/predict"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Correlation-ID", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"))
                .andExpect(content().json("""
                        {"property":{
                          "square_footage":1800,"bedrooms":3,"bathrooms":2.5,
                          "year_built":2005,"lot_size":6500,
                          "distance_to_city_center":4.125,"school_rating":8.500
                        }}
                        """))
                .andRespond(withSuccess("""
                        {"predictions":[{
                          "predicted_price":"250000.00","currency":"USD",
                          "model_version":"housing-linear-test","warnings":[]
                        }]}
                        """, MediaType.APPLICATION_JSON));

        MarketModels.Prediction prediction = gateway.predict(
                MarketFixtures.input(), "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

        assertThat(prediction.predictedPrice()).isEqualTo("250000.00");
        assertThat(prediction.modelVersion()).isEqualTo("housing-linear-test");
        server.verify();
    }

    @Test
    void structuredDownstreamRejectionIsPreserved() {
        server.expect(requestTo("http://model/api/v1/predict"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"error":{"code":"MODEL_OUTPUT_OUT_OF_DOMAIN", "message":"bad output"}}
                                """));

        assertThatThrownBy(() -> gateway.predict(MarketFixtures.input(), "correlation"))
                .isInstanceOfSatisfying(ModelServiceRejectedException.class, error -> {
                    assertThat(error.statusCode()).isEqualTo(422);
                    assertThat(error.code()).isEqualTo("MODEL_OUTPUT_OUT_OF_DOMAIN");
                    assertThat(error.getMessage()).isEqualTo("bad output");
                });
    }

    @Test
    void malformedDownstreamErrorUsesStableFallback() {
        server.expect(requestTo("http://model/api/v1/predict"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("not-json"));

        assertThatThrownBy(() -> gateway.predict(MarketFixtures.input(), "correlation"))
                .isInstanceOfSatisfying(ModelServiceRejectedException.class, error -> {
                    assertThat(error.code()).isEqualTo("MODEL_SERVICE_ERROR");
                    assertThat(error.getMessage()).contains("rejected");
                });
    }

    @Test
    void malformedSuccessResponseIsUnavailable() {
        server.expect(requestTo("http://model/api/v1/predict"))
                .andRespond(withSuccess("{\"predictions\":[]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.predict(MarketFixtures.input(), "correlation"))
                .isInstanceOf(ModelServiceException.class)
                .hasMessageContaining("invalid response");
    }

    @Test
    void transportFailureIsUnavailable() {
        server.expect(requestTo("http://model/api/v1/predict"))
                .andRespond(withException(new IOException("connection failed")));

        assertThatThrownBy(() -> gateway.predict(MarketFixtures.input(), "correlation"))
                .isInstanceOf(ModelServiceException.class)
                .hasMessageContaining("request failed");
    }
}
