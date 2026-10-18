package com.hsbc.market.export;

public final class ExportCancelledException extends RuntimeException {
  ExportCancelledException() {
    super("export was cancelled");
  }
}
