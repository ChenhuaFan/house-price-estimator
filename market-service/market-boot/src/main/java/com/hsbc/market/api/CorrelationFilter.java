package com.hsbc.market.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.market.contract.model.ApiErrorDetail;
import com.hsbc.market.contract.model.ApiErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public final class CorrelationFilter extends OncePerRequestFilter {
  private final ObjectMapper objectMapper;

  public CorrelationFilter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String supplied = request.getHeader("X-Correlation-ID");
    UUID correlationId;
    try {
      correlationId =
          supplied == null || supplied.isBlank() ? UUID.randomUUID() : UUID.fromString(supplied);
    } catch (IllegalArgumentException error) {
      correlationId = UUID.randomUUID();
      response.setStatus(400);
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.setHeader("X-Correlation-ID", correlationId.toString());
      objectMapper.writeValue(
          response.getOutputStream(),
          new ApiErrorResponse(
              new ApiErrorDetail(
                  "INVALID_CORRELATION_ID", "X-Correlation-ID must be a valid UUID."),
              correlationId));
      return;
    }
    request.setAttribute(RequestCorrelation.ATTRIBUTE, correlationId);
    response.setHeader("X-Correlation-ID", correlationId.toString());
    try (MDC.MDCCloseable ignored = MDC.putCloseable("correlation_id", correlationId.toString())) {
      chain.doFilter(request, response);
    }
  }
}
