package com.binitech.elosys.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "resilience")
public record ResilienceProperties(
    BulkDownload bulkDownload, LookupApi lookupApi, Apify apify, Llm llm) {

  public record BulkDownload(Retry retry, CircuitBreaker circuitbreaker) {}

  public record LookupApi(
      RateLimiter rateLimiter, Retry retry, CircuitBreaker circuitbreaker, Bulkhead bulkhead) {}

  public record Apify(Retry retry, CircuitBreaker circuitbreaker) {}

  public record Llm(
      Duration timeout, Retry retry, CircuitBreaker circuitbreaker, Bulkhead bulkhead) {}

  public record Retry(int maxAttempts, Duration waitDuration, double multiplier, double jitter) {}

  public record CircuitBreaker(
      int slidingWindowSize,
      float failureRateThreshold,
      Duration waitDurationOpen,
      int permittedCallsHalfOpen) {}

  public record RateLimiter(
      int limitForPeriod, Duration limitRefreshPeriod, Duration timeoutDuration) {}

  public record Bulkhead(int coreSize, int maxSize, int queueCapacity) {}
}
