package com.hsbc.market.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.market.adapter.export.CsvExportGenerator;
import com.hsbc.market.adapter.export.ExportApplicationService;
import com.hsbc.market.adapter.export.ExportWorker;
import com.hsbc.market.adapter.export.PdfExportGenerator;
import com.hsbc.market.adapter.export.storage.LocalExportStorage;
import com.hsbc.market.adapter.modelclient.ModelPredictionAdapter;
import com.hsbc.market.adapter.persistence.mybatis.MyBatisExportJobAdapter;
import com.hsbc.market.adapter.persistence.mybatis.MyBatisMarketReadAdapter;
import com.hsbc.market.adapter.persistence.mybatis.export.ExportJobMapper;
import com.hsbc.market.adapter.persistence.mybatis.export.ExportMarketQueryMapper;
import com.hsbc.market.adapter.persistence.mybatis.query.MarketQueryMapper;
import com.hsbc.market.application.MarketQueryService;
import com.hsbc.market.application.SignedCursorCodec;
import com.hsbc.market.application.WhatIfService;
import com.hsbc.market.application.port.in.ExportUseCase;
import com.hsbc.market.application.port.in.WhatIfUseCase;
import com.hsbc.market.application.port.out.ExportJobPort;
import com.hsbc.market.application.port.out.ExportStorage;
import com.hsbc.market.application.port.out.MarketReadPort;
import com.hsbc.market.application.port.out.PredictionPort;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Files;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@Configuration
public class ApplicationConfiguration {
  @Bean
  Clock marketClock() {
    return Clock.systemUTC();
  }

  @Bean
  @Primary
  MarketReadPort marketReadPort(
      MarketQueryMapper mapper,
      ObjectMapper objectMapper,
      org.springframework.cache.CacheManager cacheManager,
      MeterRegistry registry) {
    return new CachingMarketReadPort(
        new MeteredMarketReadPort(
            new MyBatisMarketReadAdapter(mapper, objectMapper), registry, "interactive"),
        cacheManager);
  }

  @Bean("exportMarketReadPort")
  MarketReadPort exportMarketReadPort(
      ExportMarketQueryMapper mapper, ObjectMapper objectMapper, MeterRegistry registry) {
    return new MeteredMarketReadPort(
        new MyBatisMarketReadAdapter(mapper, objectMapper), registry, "export");
  }

  @Bean
  ExportJobPort exportJobPort(ExportJobMapper mapper) {
    return new MyBatisExportJobAdapter(mapper);
  }

  @Bean
  ExportStorage exportStorage(MarketProperties properties, MeterRegistry registry) {
    ExportStorage storage = new LocalExportStorage(properties.export().root());
    Gauge.builder("market.export.disk.free.bytes", storage, ApplicationConfiguration::freeBytes)
        .register(registry);
    return storage;
  }

  @Bean
  MarketQueryService marketQueryService(MarketReadPort port, MarketProperties properties) {
    return new MarketQueryService(port, new SignedCursorCodec(properties.cursor().hmacSecret()));
  }

  @Bean
  @Primary
  CachingMarketFacade cachingMarketFacade(
      MarketQueryService service, org.springframework.cache.CacheManager cacheManager) {
    return new CachingMarketFacade(service, cacheManager);
  }

  @Bean
  RestClient modelRestClient(RestClient.Builder builder, MarketProperties properties) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(properties.modelService().connectTimeout());
    factory.setReadTimeout(properties.modelService().responseTimeout());
    return builder.baseUrl(properties.modelService().baseUrl()).requestFactory(factory).build();
  }

  @Bean
  PredictionPort predictionPort(
      RestClient modelRestClient,
      ObjectMapper objectMapper,
      MeterRegistry registry,
      MarketProperties properties) {
    return new ModelPredictionAdapter(
        modelRestClient, objectMapper, properties.modelService().maxConcurrency(), registry);
  }

  @Bean
  WhatIfUseCase whatIfUseCase(PredictionPort port) {
    return new WhatIfService(port);
  }

  @Bean
  ExportUseCase exportUseCase(
      ExportJobPort jobs,
      ExportStorage storage,
      MarketQueryService queries,
      Clock clock,
      MeterRegistry registry,
      MarketProperties properties) {
    return new ExportApplicationService(
        jobs,
        storage,
        queries,
        clock,
        registry,
        properties.export().retention(),
        properties.export().queueCapacity() + properties.export().workers());
  }

  @Bean
  CsvExportGenerator csvExportGenerator(
      @Qualifier("exportMarketReadPort") MarketReadPort reads,
      ExportJobPort jobs,
      ExportStorage storage,
      MarketProperties properties) {
    return new CsvExportGenerator(
        reads,
        jobs,
        storage,
        properties.export().maxBytes().toBytes(),
        properties.export().csvMaxRows(),
        properties.export().maxRuntime());
  }

  @Bean
  PdfExportGenerator pdfExportGenerator(
      @Qualifier("exportMarketReadPort") MarketReadPort reads,
      ExportJobPort jobs,
      ExportStorage storage,
      MarketProperties properties) {
    return new PdfExportGenerator(
        reads,
        jobs,
        storage,
        properties.export().maxBytes().toBytes(),
        properties.export().maxRuntime());
  }

  @Bean
  ExportWorker exportWorker(
      ExportJobPort jobs,
      ExportStorage storage,
      CsvExportGenerator csv,
      PdfExportGenerator pdf,
      ObjectMapper objectMapper,
      Clock clock,
      MeterRegistry registry,
      @Qualifier("exportTransactionManager") PlatformTransactionManager transactionManager,
      MarketProperties properties) {
    TransactionTemplate generationTransaction = new TransactionTemplate(transactionManager);
    generationTransaction.setReadOnly(true);
    generationTransaction.setTimeout(
        Math.toIntExact(properties.database().exportTimeout().toSeconds()));
    return new ExportWorker(
        jobs,
        storage,
        csv,
        pdf,
        objectMapper,
        clock,
        registry,
        properties.export().lease(),
        generationTransaction);
  }

  private static double freeBytes(ExportStorage storage) {
    try {
      return Files.getFileStore(storage.path("health/probe").getParent().getParent())
          .getUsableSpace();
    } catch (Exception error) {
      return Double.NaN;
    }
  }
}
