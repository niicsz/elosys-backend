package com.binitech.elosys.adapters.outbound.cache;

import com.binitech.elosys.adapters.events.ReadModelChangedEvent;
import com.binitech.elosys.application.ports.outbound.ReadCachePort;
import com.binitech.elosys.config.ElosysProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisReadCacheAdapter implements ReadCachePort {
  private static final Logger LOGGER = LoggerFactory.getLogger(RedisReadCacheAdapter.class);

  private final StringRedisTemplate redis;
  private final String prefix;
  private final ApplicationEventPublisher events;

  public RedisReadCacheAdapter(
      StringRedisTemplate redis, ElosysProperties properties, ApplicationEventPublisher events) {
    this.redis = redis;
    this.prefix = properties.cache().keyPrefix();
    this.events = events;
  }

  @Override
  public void evictAll() {
    try {
      long removed = 0;
      ScanOptions options = ScanOptions.scanOptions().match(prefix + "*").count(1000).build();
      try (Cursor<String> cursor = redis.scan(options)) {
        java.util.List<String> batch = new java.util.ArrayList<>(1000);
        while (cursor.hasNext()) {
          batch.add(cursor.next());
          if (batch.size() == 1000) {
            removed += unlink(batch);
            batch.clear();
          }
        }
        removed += unlink(batch);
      }
      LOGGER.info("cache de leitura invalidado ({} chaves)", removed);
    } catch (RuntimeException e) {
      LOGGER.warn("não foi possível invalidar o cache (expira pelo TTL): {}", e.toString());
    }
    events.publishEvent(new ReadModelChangedEvent("dados atualizados"));
  }

  private long unlink(java.util.List<String> keys) {
    if (keys.isEmpty()) return 0;
    Long n = redis.unlink(keys);
    return n == null ? 0 : n;
  }
}
