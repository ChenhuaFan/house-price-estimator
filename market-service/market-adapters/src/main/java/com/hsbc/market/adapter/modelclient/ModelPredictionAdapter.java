package com.hsbc.market.adapter.modelclient;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.market.application.port.out.PredictionPort;
import com.hsbc.market.domain.MarketDomain.AdvisoryWarning;
import com.hsbc.market.domain.MarketDomain.Prediction;
import com.hsbc.market.domain.MarketDomain.PropertyFeatures;
import com.hsbc.market.domain.MarketException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public final class ModelPredictionAdapter implements PredictionPort {
  private final RestClient client;
  private final ObjectMapper objectMapper;
  private final Semaphore capacity;
  private final MeterRegistry registry;

  public ModelPredictionAdapter(
      RestClient client, ObjectMapper objectMapper, int maxConcurrency, MeterRegistry registry) {
    this.client = client;
    this.objectMapper = objectMapper;
    this.capacity = new Semaphore(maxConcurrency);
    this.registry = registry;
  }

  @Override
  public Prediction predict(PropertyFeatures input, UUID correlationId) {
    if (!capacity.tryAcquire()) {
      registry.counter("market.model.bulkhead.rejections").increment();
      throw new MarketException(
          MarketException.Kind.CAPACITY,
          "CAPACITY_EXCEEDED",
          "model request capacity is exhausted");
    }
    long started = System.nanoTime();
    String outcome = "success";
    try {
      PredictResponse response =
          client
              .post()
              .uri("/api/v1/predict")
              .header("X-Correlation-ID", correlationId.toString())
              .contentType(MediaType.APPLICATION_JSON)
              .body(
                  new PredictRequest(
                      new PropertyRequest(
                          input.squareFootage(),
                          input.bedrooms(),
                          input.bathrooms(),
                          input.yearBuilt(),
                          input.lotSize(),
                          input.distanceToCityCenter(),
                          input.schoolRating())))
              .retrieve()
              .onStatus(
                  status -> status.isError(),
                  (request, clientResponse) -> {
                    byte[] body = clientResponse.getBody().readNBytes(65_536);
                    throw rejection(clientResponse.getStatusCode().value(), body);
                  })
              .body(PredictResponse.class);
      if (response == null
          || response.predictions() == null
          || response.predictions().size() != 1
          || response.predictions().getFirst() == null) {
        throw unavailable("prediction service returned an invalid response", null);
      }
      PredictionResponse prediction = response.predictions().getFirst();
      if (prediction.predictedPrice() == null
          || prediction.currency() == null
          || prediction.modelVersion() == null
          || prediction.warnings() == null) {
        throw unavailable("prediction service returned an invalid response", null);
      }
      return new Prediction(
          new BigDecimal(prediction.predictedPrice()),
          prediction.currency(),
          prediction.modelVersion(),
          prediction.warnings().stream()
              .map(
                  warning ->
                      new AdvisoryWarning(
                          warning.field(),
                          warning.submittedValue(),
                          warning.advisoryMin(),
                          warning.advisoryMax(),
                          warning.reason()))
              .toList());
    } catch (MarketException error) {
      outcome = error.kind() == MarketException.Kind.VALIDATION ? "rejected" : "unavailable";
      throw error;
    } catch (RestClientException | NumberFormatException error) {
      outcome = "unavailable";
      throw unavailable("prediction service request failed", error);
    } finally {
      capacity.release();
      Timer.builder("market.model.client.duration")
          .tag("outcome", outcome)
          .register(registry)
          .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    }
  }

  private MarketException rejection(int status, byte[] body) {
    String code = "MODEL_SERVICE_ERROR";
    String message = "Prediction service rejected the request.";
    try {
      JsonNode error =
          objectMapper.readTree(new String(body, StandardCharsets.UTF_8)).path("error");
      if (error.path("code").isTextual()) code = error.path("code").asText();
      if (error.path("message").isTextual()) message = error.path("message").asText();
    } catch (Exception ignored) {
      // Stable fallback deliberately hides an invalid downstream body.
    }
    if (status >= 400 && status < 500) {
      return new MarketException(MarketException.Kind.VALIDATION, code, message);
    }
    return unavailable("Prediction service is unavailable.", null);
  }

  private static MarketException unavailable(String message, Throwable cause) {
    return new MarketException(
        MarketException.Kind.DEPENDENCY, "MODEL_SERVICE_UNAVAILABLE", message, cause);
  }

  private record PropertyRequest(
      @JsonProperty("square_footage") long squareFootage,
      int bedrooms,
      BigDecimal bathrooms,
      @JsonProperty("year_built") int yearBuilt,
      @JsonProperty("lot_size") long lotSize,
      @JsonProperty("distance_to_city_center") BigDecimal distanceToCityCenter,
      @JsonProperty("school_rating") BigDecimal schoolRating) {}

  private record PredictRequest(PropertyRequest property) {}

  private record PredictResponse(List<PredictionResponse> predictions) {}

  private record PredictionResponse(
      @JsonProperty("predicted_price") String predictedPrice,
      String currency,
      @JsonProperty("model_version") String modelVersion,
      List<WarningResponse> warnings) {}

  private record WarningResponse(
      String field,
      @JsonProperty("submitted_value") String submittedValue,
      @JsonProperty("advisory_min") String advisoryMin,
      @JsonProperty("advisory_max") String advisoryMax,
      String reason) {}
}
