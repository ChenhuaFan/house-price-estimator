package com.hsbc.market.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/** Validated runtime settings for databases, prediction, exports, and cursors. */
@Validated
@ConfigurationProperties("market")
public record MarketProperties(
    @Valid @NotNull Database database,
    @Valid @NotNull ModelService modelService,
    @Valid @NotNull Export export,
    @Valid @NotNull Cursor cursor,
    @NotBlank String portalOrigin) {

  public record Database(
      @NotBlank String url,
      @NotBlank String username,
      @NotBlank String password,
      @Min(1) @Max(32) int queryPoolSize,
      @Min(1) @Max(8) int exportPoolSize,
      @NotNull Duration connectionTimeout,
      @NotNull Duration queryTimeout,
      @NotNull Duration exportTimeout) {}

  public record ModelService(
      @NotBlank String baseUrl,
      @NotNull Duration connectTimeout,
      @NotNull Duration responseTimeout,
      @Min(1) @Max(64) int maxConcurrency) {}

  public record Export(
      @NotNull Path root,
      @Min(1) @Max(8) int workers,
      @Min(1) @Max(1000) int queueCapacity,
      @NotNull Duration retention,
      @NotNull DataSize maxBytes,
      @NotNull Duration maxRuntime,
      @Min(1) int csvMaxRows,
      @NotNull Duration lease) {}

  public record Cursor(@NotBlank String hmacSecret) {}
}
