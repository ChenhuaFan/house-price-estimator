package com.hsbc.market;

import static com.hsbc.market.MarketModels.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class ModelServiceGateway implements PredictionGateway {
    private final RestClient client;
    private final ObjectMapper objectMapper;

    @Autowired
    public ModelServiceGateway(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            @Value("${model-service.url}") String modelUrl,
            @Value("${model-service.connect-timeout}") Duration connectTimeout,
            @Value("${model-service.response-timeout}") Duration responseTimeout) {
        this(buildClient(builder, modelUrl, connectTimeout, responseTimeout), objectMapper);
    }

    ModelServiceGateway(RestClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    private static RestClient buildClient(
            RestClient.Builder builder,
            String modelUrl,
            Duration connectTimeout,
            Duration responseTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(responseTimeout);
        return builder.baseUrl(modelUrl).requestFactory(requestFactory).build();
    }

    @Override
    public Prediction predict(PropertyInput input, String correlationId) {
        try {
            PredictResponse response = client.post()
                    .uri("/api/v1/predict")
                    .header("X-Correlation-ID", correlationId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new PredictRequest(input))
                    .retrieve()
                    .onStatus(status -> status.isError(), (request, clientResponse) -> {
                        throw rejection(
                                clientResponse.getStatusCode().value(),
                                clientResponse.getBody().readAllBytes());
                    })
                    .body(PredictResponse.class);
            if (response == null
                    || response.predictions() == null
                    || response.predictions().size() != 1
                    || response.predictions().getFirst() == null) {
                throw new ModelServiceException("Prediction service returned an invalid response");
            }
            return response.predictions().getFirst();
        } catch (ModelServiceRejectedException | ModelServiceException error) {
            throw error;
        } catch (RestClientException error) {
            throw new ModelServiceException("Prediction service request failed", error);
        }
    }

    private ModelServiceRejectedException rejection(int statusCode, byte[] body) {
        String code = "MODEL_SERVICE_ERROR";
        String message = "Prediction service rejected the request.";
        try {
            JsonNode error = objectMapper
                    .readTree(new String(body, StandardCharsets.UTF_8))
                    .path("error");
            if (error.path("code").isTextual()) {
                code = error.path("code").asText();
            }
            if (error.path("message").isTextual()) {
                message = error.path("message").asText();
            }
        } catch (IOException ignored) {
            // Fall back to the stable generic downstream error contract.
        }
        return new ModelServiceRejectedException(statusCode, code, message);
    }
}
