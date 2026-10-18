package com.hsbc.market.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CacheConfigurationTest {
  @Test
  void cacheExpiresOnTheConfiguredClockAndEvictsAtItsSizeBound() {
    AtomicLong nanos = new AtomicLong();
    Ticker ticker = nanos::get;
    var cache = CacheConfiguration.cache("test", 2, Duration.ofMinutes(5), ticker);

    cache.put("one", 1);
    cache.put("two", 2);
    assertThat(cache.get("one", Integer.class)).isEqualTo(1);

    cache.put("three", 3);
    cache.getNativeCache().cleanUp();
    assertThat(cache.getNativeCache().estimatedSize()).isLessThanOrEqualTo(2);

    nanos.addAndGet(TimeUnit.MINUTES.toNanos(6));
    cache.getNativeCache().cleanUp();
    assertThat(cache.get("one")).isNull();
    assertThat(cache.get("two")).isNull();
    assertThat(cache.get("three")).isNull();
    assertThat(cache.getNativeCache().stats().evictionCount()).isGreaterThan(0);
  }
}
