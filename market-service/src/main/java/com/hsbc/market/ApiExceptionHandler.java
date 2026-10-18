package com.hsbc.market;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler({IllegalArgumentException.class})
    ResponseEntity<Map<String, Object>> badRequest(
            IllegalArgumentException error, HttpServletRequest request) {
        return response(400, "VALIDATION_ERROR", error.getMessage(), request, List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> invalidBody(
            MethodArgumentNotValidException error, HttpServletRequest request) {
        List<Map<String, String>> fields = error.getBindingResult().getFieldErrors().stream()
                .map(this::fieldError)
                .toList();
        return response(422, "VALIDATION_ERROR", "Request body is invalid.", request, fields);
    }

    @ExceptionHandler(ModelServiceRejectedException.class)
    ResponseEntity<Map<String, Object>> modelRejected(
            ModelServiceRejectedException error, HttpServletRequest request) {
        return response(error.statusCode(), error.code(), error.getMessage(), request, List.of());
    }

    @ExceptionHandler(ModelServiceException.class)
    ResponseEntity<Map<String, Object>> modelUnavailable(
            ModelServiceException error, HttpServletRequest request) {
        return response(503, "MODEL_SERVICE_UNAVAILABLE",
                "Prediction is temporarily unavailable. Please retry.", request, List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> internal(Exception error, HttpServletRequest request) {
        LOGGER.error("Unhandled market service error", error);
        return response(500, "INTERNAL_ERROR",
                "The market request could not be completed.", request, List.of());
    }

    private ResponseEntity<Map<String, Object>> response(
            int status, String code, String message, HttpServletRequest request,
            List<Map<String, String>> fields) {
        Object correlation = request.getAttribute(CorrelationFilter.ATTRIBUTE);
        String correlationId = correlation == null ? UUID.randomUUID().toString() : correlation.toString();
        Map<String, Object> detail = fields.isEmpty()
                ? Map.of("code", code, "message", message)
                : Map.of("code", code, "message", message, "fields", fields);
        return ResponseEntity.status(status)
                .header("X-Correlation-ID", correlationId)
                .body(Map.of("error", detail, "correlation_id", correlationId));
    }

    private Map<String, String> fieldError(FieldError error) {
        return Map.of("field", error.getField(), "message",
                error.getDefaultMessage() == null ? "invalid value" : error.getDefaultMessage());
    }
}
