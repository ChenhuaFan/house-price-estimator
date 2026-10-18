package com.hsbc.market.export;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Rejects writes that would exceed an export's configured byte limit. */
final class LimitedOutputStream extends FilterOutputStream {
  private final long maximum;
  private long count;

  LimitedOutputStream(OutputStream output, long maximum) {
    super(output);
    this.maximum = maximum;
  }

  @Override
  public void write(int value) throws IOException {
    requireCapacity(1);
    out.write(value);
    count++;
  }

  @Override
  public void write(byte[] bytes, int offset, int length) throws IOException {
    requireCapacity(length);
    out.write(bytes, offset, length);
    count += length;
  }

  long count() {
    return count;
  }

  private void requireCapacity(int additional) throws IOException {
    if (additional < 0 || count > maximum - additional) {
      throw new IOException("export exceeded the configured byte limit");
    }
  }
}
