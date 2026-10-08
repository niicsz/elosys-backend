package com.binitech.elosys.application.usecases;

import java.util.Locale;
import org.slf4j.Logger;

public final class Progress {
  private final Logger logger;
  private final String label;
  private final long every;
  private long count;

  public Progress(Logger logger, String label, long every) {
    this.logger = logger;
    this.label = label;
    this.every = every;
  }

  public Progress(Logger logger, String label) {
    this(logger, label, 250_000);
  }

  public void tick() {
    count++;
    if (count % every == 0)
      logger.info("  {}: {} linhas", label, String.format(Locale.ROOT, "%,d", count));
  }

  public long count() {
    return count;
  }

  public long done() {
    logger.info("  {}: {} linhas no total", label, String.format(Locale.ROOT, "%,d", count));
    return count;
  }

  public static String humanBytes(long bytes) {
    double size = bytes;
    for (String unit : new String[] {"B", "KB", "MB"}) {
      if (size < 1024) return String.format(Locale.ROOT, "%.1f %s", size, unit);
      size /= 1024;
    }
    return String.format(Locale.ROOT, "%.1f GB", size);
  }

  public static String brl(long cents) {
    return String.format(Locale.ROOT, "R$ %,.2f", cents / 100.0);
  }
}
