package com.hsbc.market.config;

import com.hsbc.market.export.storage.LocalExportStorage;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.nio.file.Files;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Wires shared clocks, downstream clients, and export disk metrics. */
@Configuration
public class ApplicationConfiguration {
  @Bean
  Clock marketClock() {
    return Clock.systemUTC();
  }

  @Bean
  RestClient modelRestClient(RestClient.Builder builder, MarketProperties properties) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(properties.modelService().connectTimeout());
    factory.setReadTimeout(properties.modelService().responseTimeout());
    return builder.baseUrl(properties.modelService().baseUrl()).requestFactory(factory).build();
  }

  @Bean
  MeterBinder exportDiskSpaceMetrics(LocalExportStorage storage) {
    return registry ->
        Gauge.builder("market.export.disk.free.bytes", storage, ApplicationConfiguration::freeBytes)
            .register(registry);
  }

  private static double freeBytes(LocalExportStorage storage) {
    try {
      return Files.getFileStore(storage.path("health/probe").getParent().getParent())
          .getUsableSpace();
    } catch (Exception error) {
      return Double.NaN;
    }
  }
}
