package com.hsbc.market.config;

import com.hsbc.market.adapter.export.ExportWorker;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "market.export.scheduling-enabled",
    havingValue = "true",
    matchIfMissing = true)
public final class ExportWorkerScheduler implements AutoCloseable {
  private final ExportWorker worker;
  private final ExecutorService executor;
  private final Semaphore availableWorkers;

  public ExportWorkerScheduler(ExportWorker worker, MarketProperties properties) {
    this.worker = worker;
    int workers = properties.export().workers();
    this.availableWorkers = new Semaphore(workers);
    this.executor =
        Executors.newFixedThreadPool(
            workers,
            runnable -> {
              Thread thread = new Thread(runnable, "market-export-worker");
              thread.setDaemon(true);
              return thread;
            });
  }

  @Scheduled(fixedDelayString = "${market.export.poll-interval:500ms}")
  void dispatch() {
    while (availableWorkers.tryAcquire()) {
      try {
        executor.submit(
            () -> {
              try {
                while (worker.runOnce()) {}
              } finally {
                availableWorkers.release();
              }
            });
      } catch (RuntimeException error) {
        availableWorkers.release();
        throw error;
      }
    }
  }

  @Scheduled(fixedDelayString = "${market.export.recovery-interval:30s}")
  void recover() {
    worker.recoverExpiredLeases();
  }

  @Scheduled(fixedDelayString = "${market.export.cleanup-interval:10m}")
  void cleanup() {
    worker.cleanupExpired();
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }
}
