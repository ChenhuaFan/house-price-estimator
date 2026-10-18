package com.hsbc.market.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hsbc.market.service.ExportWorkerService;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class ExportWorkerSchedulerTest {
  @Test
  void dispatchUsesEveryConfiguredWorkerSlot() throws Exception {
    ExportWorkerService worker = mock(ExportWorkerService.class);
    CountDownLatch started = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger active = new AtomicInteger();
    AtomicInteger maximum = new AtomicInteger();
    when(worker.runOnce())
        .thenAnswer(
            invocation -> {
              int current = active.incrementAndGet();
              maximum.accumulateAndGet(current, Math::max);
              started.countDown();
              try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("worker release timed out");
                }
              } finally {
                active.decrementAndGet();
              }
              return false;
            });

    try (ExportWorkerScheduler scheduler = new ExportWorkerScheduler(worker, properties(2))) {
      scheduler.dispatch();
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(maximum).hasValue(2);
      release.countDown();
    } finally {
      release.countDown();
    }
  }

  private static MarketProperties properties(int workers) {
    return new MarketProperties(
        null,
        null,
        new MarketProperties.Export(
            Path.of("target/test-exports"),
            workers,
            20,
            Duration.ofHours(1),
            DataSize.ofMegabytes(10),
            Duration.ofMinutes(1),
            1000,
            Duration.ofMinutes(2)),
        null,
        "http://localhost");
  }
}
