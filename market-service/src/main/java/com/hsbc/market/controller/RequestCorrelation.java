package com.hsbc.market.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class RequestCorrelation {
  public static final String ATTRIBUTE = RequestCorrelation.class.getName() + ".id";
  private final HttpServletRequest request;

  public RequestCorrelation(HttpServletRequest request) {
    this.request = request;
  }

  public UUID current() {
    Object value = request.getAttribute(ATTRIBUTE);
    return value instanceof UUID uuid ? uuid : UUID.randomUUID();
  }
}
