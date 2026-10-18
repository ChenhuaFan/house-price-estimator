package com.hsbc.market.adapter.export;

final class ExportCancelledException extends RuntimeException {
  ExportCancelledException() {
    super("export was cancelled");
  }
}
