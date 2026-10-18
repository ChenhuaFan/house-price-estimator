package com.hsbc.market.api;

import com.hsbc.market.contract.model.ApiErrorDetail;
import com.hsbc.market.contract.model.ApiErrorResponse;
import com.hsbc.market.contract.model.FieldViolation;
import com.hsbc.market.domain.MarketException;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public final class GlobalExceptionHandler {
  private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);
  private final RequestCorrelation correlation;

  public GlobalExceptionHandler(RequestCorrelation correlation) {
    this.correlation = correlation;
  }

  @ExceptionHandler(MarketException.class)
  ResponseEntity<ApiErrorResponse> market(MarketException error) {
    HttpStatus status =
        switch (error.kind()) {
          case MALFORMED -> HttpStatus.BAD_REQUEST;
          case VALIDATION -> HttpStatus.UNPROCESSABLE_ENTITY;
          case NOT_FOUND -> HttpStatus.NOT_FOUND;
          case CONFLICT -> HttpStatus.CONFLICT;
          case CAPACITY -> HttpStatus.TOO_MANY_REQUESTS;
          case GONE -> HttpStatus.GONE;
          case DEPENDENCY -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    List<FieldViolation> fields =
        error.fields().stream()
            .map(field -> new FieldViolation(field.field(), field.message()))
            .toList();
    return response(status, error.code(), error.getMessage(), fields);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiErrorResponse> invalidBody(MethodArgumentNotValidException error) {
    List<FieldViolation> fields =
        error.getBindingResult().getFieldErrors().stream()
            .map(
                field ->
                    new FieldViolation(
                        field.getField(),
                        field.getDefaultMessage() == null
                            ? "invalid value"
                            : field.getDefaultMessage()))
            .toList();
    return response(
        HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR", "Request body is invalid.", fields);
  }

  @ExceptionHandler({HandlerMethodValidationException.class, ConstraintViolationException.class})
  ResponseEntity<ApiErrorResponse> invalidParameters(Exception error) {
    return response(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "VALIDATION_ERROR",
        "Request parameters are invalid.",
        List.of());
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    MissingRequestHeaderException.class
  })
  ResponseEntity<ApiErrorResponse> malformed(Exception error) {
    return response(
        HttpStatus.BAD_REQUEST,
        "MALFORMED_REQUEST",
        "Request syntax or types are invalid.",
        List.of());
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiErrorResponse> unexpected(Exception error) {
    LOGGER.error("Unhandled market-service failure", error);
    return response(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "INTERNAL_ERROR",
        "The request could not be completed.",
        List.of());
  }

  private ResponseEntity<ApiErrorResponse> response(
      HttpStatus status, String code, String message, List<FieldViolation> fields) {
    UUID id = correlation.current();
    ApiErrorDetail detail = new ApiErrorDetail(code, message);
    if (!fields.isEmpty()) detail.setFields(fields);
    return ResponseEntity.status(status)
        .header("X-Correlation-ID", id.toString())
        .body(new ApiErrorResponse(detail, id));
  }
}
