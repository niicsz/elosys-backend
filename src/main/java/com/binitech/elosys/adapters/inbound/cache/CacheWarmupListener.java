package com.binitech.elosys.adapters.inbound.cache;

import com.binitech.elosys.adapters.events.ReadModelChangedEvent;
import com.binitech.elosys.application.ports.inbound.ReadModelQueryPort;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnWebApplication
public class CacheWarmupListener {
  private static final Logger LOGGER = LoggerFactory.getLogger(CacheWarmupListener.class);

  private final ReadModelQueryPort queries;
  private final boolean enabled;
  private final AtomicBoolean running = new AtomicBoolean();

  public CacheWarmupListener(
      ReadModelQueryPort queries, @Value("${elosys.cache.warmup:true}") boolean enabled) {
    this.queries = queries;
    this.enabled = enabled;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void onStartup() {
    warmInBackground("startup");
  }

  @EventListener
  public void onDataChanged(ReadModelChangedEvent event) {
    warmInBackground(event.reason());
  }

  private void warmInBackground(String reason) {
    if (!enabled || !running.compareAndSet(false, true)) return;
    Thread.ofVirtual().name("cache-warmup").start(() -> warm(reason));
  }

  private void warm(String reason) {
    long start = System.nanoTime();
    List<Supplier<Object>> screens = new ArrayList<>();
    screens.add(queries::meta);
    screens.add(() -> queries.home(null));
    screens.add(() -> queries.assetsRanking("bens", null, 1));
    screens.add(() -> queries.assetsRanking("crescimento", null, 1));
    screens.add(() -> queries.earmarkPayments(1, "", "desc"));
    screens.add(() -> queries.circularDonations(null, null, 1));
    screens.add(() -> queries.aiReviews(null, null, 1));
    screens.add(() -> queries.supplierPartners(null, null, 1));
    screens.add(() -> queries.discourse(null, false, null, null, null, 1));
    screens.add(() -> queries.disproportionateExpenses(null, null, 1));
    int ok = 0;
    try {
      for (Supplier<Object> screen : screens) {
        try {
          screen.get();
          ok++;
        } catch (RuntimeException e) {
          LOGGER.warn("pré-aquecimento: tela falhou: {}", e.toString());
        }
      }
      Object years = queries.meta().get("expenseYears");
      if (years instanceof List<?> list)
        for (Object y : list)
          if (y instanceof Number n) {
            queries.home(n.intValue());
            ok++;
          }
    } finally {
      running.set(false);
    }
    LOGGER.info(
        "cache pré-aquecido ({}): {} telas em {} s",
        reason,
        ok,
        (System.nanoTime() - start) / 1_000_000_000);
  }
}
