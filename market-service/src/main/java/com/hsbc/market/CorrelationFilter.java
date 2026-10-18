package com.hsbc.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class CorrelationFilter extends OncePerRequestFilter {
    public static final String ATTRIBUTE = "correlationId";
    private final ObjectMapper mapper;

    public CorrelationFilter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader("X-Correlation-ID");
        String correlationId;
        try {
            correlationId = supplied == null || supplied.isBlank()
                    ? UUID.randomUUID().toString()
                    : UUID.fromString(supplied).toString();
        } catch (IllegalArgumentException error) {
            correlationId = UUID.randomUUID().toString();
            response.setStatus(400);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("X-Correlation-ID", correlationId);
            mapper.writeValue(response.getOutputStream(), Map.of(
                    "error", Map.of(
                            "code", "INVALID_CORRELATION_ID",
                            "message", "X-Correlation-ID must be a valid UUID."),
                    "correlation_id", correlationId));
            return;
        }
        request.setAttribute(ATTRIBUTE, correlationId);
        response.setHeader("X-Correlation-ID", correlationId);
        try (MDC.MDCCloseable ignored = MDC.putCloseable("correlationId", correlationId)) {
            chain.doFilter(request, response);
        }
    }
}
