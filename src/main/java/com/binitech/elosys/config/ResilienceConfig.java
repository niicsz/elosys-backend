package com.binitech.elosys.config;

import com.binitech.elosys.adapters.outbound.resilience.TransientFailure;
import com.binitech.elosys.domain.exception.SourceBlockedException;
import com.binitech.elosys.domain.exception.SourceNotFoundException;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRateLimiterMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedTimeLimiterMetrics;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ResilienceConfig {
  public static final String BULK_DOWNLOAD = "bulk-download";
  public static final String LOOKUP_API = "lookup-api";
  public static final String APIFY = "apify";
  public static final String LLM = "llm";

  private final ResilienceProperties props;

  public ResilienceConfig(ResilienceProperties props) {
    this.props = props;
  }

  @Bean
  public CircuitBreakerRegistry circuitBreakerRegistry() {
    CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
    registry.circuitBreaker(BULK_DOWNLOAD, circuitBreaker(props.bulkDownload().circuitbreaker()));
    registry.circuitBreaker(LOOKUP_API, circuitBreaker(props.lookupApi().circuitbreaker()));
    registry.circuitBreaker(APIFY, circuitBreaker(props.apify().circuitbreaker()));
    registry.circuitBreaker(LLM, circuitBreaker(props.llm().circuitbreaker()));
    return registry;
  }

  @Bean
  public RetryRegistry retryRegistry() {
    RetryRegistry registry = RetryRegistry.ofDefaults();
    registry.retry(BULK_DOWNLOAD, retry(props.bulkDownload().retry(), TransientFailure.class));
    registry.retry(LOOKUP_API, retry(props.lookupApi().retry(), TransientFailure.class));
    registry.retry(APIFY, retry(props.apify().retry(), TransientFailure.class));
    registry.retry(
        LLM,
        retry(
            props.llm().retry(),
            TransientFailure.class,
            java.util.concurrent.TimeoutException.class));
    return registry;
  }

  @Bean
  public RateLimiterRegistry rateLimiterRegistry() {
    ResilienceProperties.RateLimiter rl = props.lookupApi().rateLimiter();
    RateLimiterRegistry registry = RateLimiterRegistry.ofDefaults();
    registry.rateLimiter(
        LOOKUP_API,
        RateLimiterConfig.custom()
            .limitForPeriod(rl.limitForPeriod())
            .limitRefreshPeriod(rl.limitRefreshPeriod())
            .timeoutDuration(rl.timeoutDuration())
            .build());
    return registry;
  }

  @Bean
  public BulkheadRegistry bulkheadRegistry() {
    BulkheadRegistry registry = BulkheadRegistry.ofDefaults();
    registry.bulkhead(LOOKUP_API, bulkhead(props.lookupApi().bulkhead()));
    registry.bulkhead(LLM, bulkhead(props.llm().bulkhead()));
    return registry;
  }

  @Bean
  public TimeLimiterRegistry timeLimiterRegistry() {
    TimeLimiterRegistry registry = TimeLimiterRegistry.ofDefaults();
    registry.timeLimiter(
        LLM,
        TimeLimiterConfig.custom()
            .timeoutDuration(props.llm().timeout())
            .cancelRunningFuture(true)
            .build());
    return registry;
  }

  @Bean(destroyMethod = "shutdown")
  public ExecutorService resilienceExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  @Bean
  public TaggedCircuitBreakerMetrics circuitBreakerMetrics(
      CircuitBreakerRegistry registry, MeterRegistry meterRegistry) {
    TaggedCircuitBreakerMetrics metrics =
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry);
    metrics.bindTo(meterRegistry);
    return metrics;
  }

  @Bean
  public TaggedRetryMetrics retryMetrics(RetryRegistry registry, MeterRegistry meterRegistry) {
    TaggedRetryMetrics metrics = TaggedRetryMetrics.ofRetryRegistry(registry);
    metrics.bindTo(meterRegistry);
    return metrics;
  }

  @Bean
  public TaggedRateLimiterMetrics rateLimiterMetrics(
      RateLimiterRegistry registry, MeterRegistry meterRegistry) {
    TaggedRateLimiterMetrics metrics = TaggedRateLimiterMetrics.ofRateLimiterRegistry(registry);
    metrics.bindTo(meterRegistry);
    return metrics;
  }

  @Bean
  public TaggedBulkheadMetrics bulkheadMetrics(
      BulkheadRegistry registry, MeterRegistry meterRegistry) {
    TaggedBulkheadMetrics metrics = TaggedBulkheadMetrics.ofBulkheadRegistry(registry);
    metrics.bindTo(meterRegistry);
    return metrics;
  }

  @Bean
  public TaggedTimeLimiterMetrics timeLimiterMetrics(
      TimeLimiterRegistry registry, MeterRegistry meterRegistry) {
    TaggedTimeLimiterMetrics metrics = TaggedTimeLimiterMetrics.ofTimeLimiterRegistry(registry);
    metrics.bindTo(meterRegistry);
    return metrics;
  }

  private static CircuitBreakerConfig circuitBreaker(ResilienceProperties.CircuitBreaker cb) {
    return CircuitBreakerConfig.custom()
        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
        .slidingWindowSize(cb.slidingWindowSize())
        .failureRateThreshold(cb.failureRateThreshold())
        .waitDurationInOpenState(cb.waitDurationOpen())
        .permittedNumberOfCallsInHalfOpenState(cb.permittedCallsHalfOpen())
        .ignoreExceptions(SourceBlockedException.class, SourceNotFoundException.class)
        .build();
  }

  @SafeVarargs
  private static RetryConfig retry(
      ResilienceProperties.Retry retry, Class<? extends Throwable>... retryOn) {
    return RetryConfig.custom()
        .maxAttempts(retry.maxAttempts())
        .intervalFunction(
            IntervalFunction.ofExponentialRandomBackoff(
                retry.waitDuration(), retry.multiplier(), retry.jitter()))
        .retryExceptions(retryOn)
        .ignoreExceptions(
            CallNotPermittedException.class,
            SourceBlockedException.class,
            SourceNotFoundException.class)
        .build();
  }

  private static BulkheadConfig bulkhead(ResilienceProperties.Bulkhead bh) {
    return BulkheadConfig.custom()
        .maxConcurrentCalls(bh.maxSize())
        .maxWaitDuration(java.time.Duration.ofMinutes(10))
        .build();
  }
}
