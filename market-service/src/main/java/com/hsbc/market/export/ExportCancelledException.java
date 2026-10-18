package com.hsbc.market.export;

/** Stops export generation after a persisted cancellation request is observed. */
public final class ExportCancelledException extends RuntimeException {
  ExportCancelledException() {
    super("export was cancelled");
  }
}
