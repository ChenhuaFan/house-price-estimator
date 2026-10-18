package com.hsbc.market.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.List;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Defines bounded in-memory caches for immutable query results. */
@EnableCaching
@Configuration
public class CacheConfiguration {
  @Bean
  CacheManager cacheManager() {
    SimpleCacheManager manager = new SimpleCacheManager();
    manager.setCaches(
        List.of(
            cache("statistics", 500, Duration.ofMinutes(5)),
            cache("firstPage", 200, Duration.ofMinutes(1)),
            cache("datasetVersion", 8, Duration.ofSeconds(30))));
    return manager;
  }

  private static CaffeineCache cache(String name, long maximumSize, Duration ttl) {
    return cache(name, maximumSize, ttl, Ticker.systemTicker());
  }

  static CaffeineCache cache(String name, long maximumSize, Duration ttl, Ticker ticker) {
    return new CaffeineCache(
        name,
        Caffeine.newBuilder()
            .maximumSize(maximumSize)
            .expireAfterWrite(ttl)
            .ticker(ticker)
            .recordStats()
            .build());
  }
}
