package com.hsbc.market.exception;

import java.util.List;

/** Carries stable error categories, codes, and field violations across service layers. */
public final class MarketException extends RuntimeException {
  public enum Kind {
    MALFORMED,
    VALIDATION,
    NOT_FOUND,
    CONFLICT,
    CAPACITY,
    GONE,
    DEPENDENCY
  }

  public record FieldViolation(String field, String message) {}

  private final Kind kind;
  private final String code;
  private final List<FieldViolation> fields;

  public MarketException(Kind kind, String code, String message) {
    this(kind, code, message, List.of(), null);
  }

  public MarketException(Kind kind, String code, String message, Throwable cause) {
    this(kind, code, message, List.of(), cause);
  }

  public MarketException(
      Kind kind, String code, String message, List<FieldViolation> fields, Throwable cause) {
    super(message, cause);
    this.kind = kind;
    this.code = code;
    this.fields = List.copyOf(fields);
  }

  public Kind kind() {
    return kind;
  }

  public String code() {
    return code;
  }

  public List<FieldViolation> fields() {
    return fields;
  }

  public static MarketException validation(String code, String message) {
    return new MarketException(Kind.VALIDATION, code, message);
  }

  public static MarketException notFound(String message) {
    return new MarketException(Kind.NOT_FOUND, "RESOURCE_NOT_FOUND", message);
  }

  public static MarketException conflict(String code, String message) {
    return new MarketException(Kind.CONFLICT, code, message);
  }
}
