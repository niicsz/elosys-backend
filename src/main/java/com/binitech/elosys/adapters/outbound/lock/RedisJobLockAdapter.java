package com.binitech.elosys.adapters.outbound.lock;

import com.binitech.elosys.application.ports.outbound.JobLockPort;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisJobLockAdapter implements JobLockPort {
  private static final Logger LOGGER = LoggerFactory.getLogger(RedisJobLockAdapter.class);
  private static final String KEY = "elosys:lock:writer";
  private static final Duration TTL = Duration.ofMinutes(2);
  private static final Duration RENEW_EVERY = Duration.ofSeconds(30);

  private static final RedisScript<Long> RELEASE =
      RedisScript.of(
          "if string.sub(redis.call('get', KEYS[1]) or '', 1, string.len(ARGV[1])) == ARGV[1]"
              + " then return redis.call('del', KEYS[1]) else return 0 end",
          Long.class);
  private static final RedisScript<Long> RENEW =
      RedisScript.of(
          "if string.sub(redis.call('get', KEYS[1]) or '', 1, string.len(ARGV[1])) == ARGV[1]"
              + " then return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end",
          Long.class);

  private final StringRedisTemplate redis;
  private final ScheduledExecutorService watchdog =
      Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
  private final Map<String, ScheduledFuture<?>> renewals = new ConcurrentHashMap<>();

  public RedisJobLockAdapter(StringRedisTemplate redis) {
    this.redis = redis;
  }

  @Override
  public Optional<String> tryAcquire(String holder) {
    String token = UUID.randomUUID().toString();
    Boolean ok = redis.opsForValue().setIfAbsent(KEY, token + "|" + holder, TTL);
    if (!Boolean.TRUE.equals(ok)) return Optional.empty();
    renewals.put(
        token,
        watchdog.scheduleAtFixedRate(
            () -> renew(token),
            RENEW_EVERY.toMillis(),
            RENEW_EVERY.toMillis(),
            TimeUnit.MILLISECONDS));
    return Optional.of(token);
  }

  @Override
  public void release(String token) {
    ScheduledFuture<?> renewal = renewals.remove(token);
    if (renewal != null) renewal.cancel(false);
    try {
      redis.execute(RELEASE, List.of(KEY), token + "|");
    } catch (RuntimeException e) {
      LOGGER.warn("não foi possível liberar a trava (expira sozinha em {}): {}", TTL, e.toString());
    }
  }

  @Override
  public Optional<String> currentHolder() {
    String value = redis.opsForValue().get(KEY);
    if (value == null) return Optional.empty();
    int bar = value.indexOf('|');
    return Optional.of(bar >= 0 ? value.substring(bar + 1) : value);
  }

  private void renew(String token) {
    try {
      redis.execute(RENEW, List.of(KEY), token + "|", String.valueOf(TTL.toMillis()));
    } catch (RuntimeException e) {
      LOGGER.warn("falha ao renovar a trava do job: {}", e.toString());
    }
  }

  @PreDestroy
  void shutdown() {
    watchdog.shutdownNow();
  }
}
