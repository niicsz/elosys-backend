package com.binitech.elosys.adapters.outbound.resilience;

import static com.binitech.elosys.config.ResilienceConfig.APIFY;
import static com.binitech.elosys.config.ResilienceConfig.BULK_DOWNLOAD;
import static com.binitech.elosys.config.ResilienceConfig.LLM;
import static com.binitech.elosys.config.ResilienceConfig.LOOKUP_API;

import com.binitech.elosys.domain.exception.BusinessException;
import com.binitech.elosys.domain.exception.LanguageModelUnavailableException;
import com.binitech.elosys.domain.exception.SourceUnavailableException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ResilienceFacade {
  private static final Logger LOGGER = LoggerFactory.getLogger(ResilienceFacade.class);

  private final CircuitBreakerRegistry circuitBreakers;
  private final RetryRegistry retries;
  private final RateLimiterRegistry rateLimiters;
  private final BulkheadRegistry bulkheads;
  private final TimeLimiterRegistry timeLimiters;
  private final ExecutorService executor;

  public ResilienceFacade(
      CircuitBreakerRegistry circuitBreakers,
      RetryRegistry retries,
      RateLimiterRegistry rateLimiters,
      BulkheadRegistry bulkheads,
      TimeLimiterRegistry timeLimiters,
      ExecutorService resilienceExecutor) {
    this.circuitBreakers = circuitBreakers;
    this.retries = retries;
    this.rateLimiters = rateLimiters;
    this.bulkheads = bulkheads;
    this.timeLimiters = timeLimiters;
    this.executor = resilienceExecutor;
  }

  public <T> T bulkDownload(String what, Supplier<T> call) {
    Supplier<T> decorated = CircuitBreaker.decorateSupplier(cb(BULK_DOWNLOAD), call);
    decorated = Retry.decorateSupplier(retries.retry(BULK_DOWNLOAD), decorated);
    return sourceCall(what, decorated);
  }

  public <T> T lookup(String what, Supplier<T> call) {
    Supplier<T> decorated = Bulkhead.decorateSupplier(bulkheads.bulkhead(LOOKUP_API), call);
    RateLimiter limiter = rateLimiters.rateLimiter(LOOKUP_API);
    decorated = RateLimiter.decorateSupplier(limiter, decorated);
    decorated = CircuitBreaker.decorateSupplier(cb(LOOKUP_API), decorated);
    decorated = Retry.decorateSupplier(retries.retry(LOOKUP_API), decorated);
    return sourceCall(what, decorated);
  }

  public <T> T apify(String what, Supplier<T> call) {
    Supplier<T> decorated = CircuitBreaker.decorateSupplier(cb(APIFY), call);
    decorated = Retry.decorateSupplier(retries.retry(APIFY), decorated);
    return sourceCall(what, decorated);
  }

  public <T> T llm(Supplier<T> call) {
    TimeLimiter timeLimiter = timeLimiters.timeLimiter(LLM);
    Callable<T> timed =
        TimeLimiter.decorateFutureSupplier(
            timeLimiter, () -> CompletableFuture.supplyAsync(call, executor));
    Callable<T> decorated = Bulkhead.decorateCallable(bulkheads.bulkhead(LLM), timed);
    decorated = CircuitBreaker.decorateCallable(cb(LLM), decorated);
    decorated = Retry.decorateCallable(retries.retry(LLM), decorated);
    try {
      return decorated.call();
    } catch (CallNotPermittedException e) {
      LOGGER.warn("disjuntor do modelo aberto — chamada recusada");
      throw new LanguageModelUnavailableException("disjuntor do modelo de linguagem aberto", e);
    } catch (TimeoutException e) {
      throw new LanguageModelUnavailableException("modelo de linguagem não respondeu a tempo", e);
    } catch (BulkheadFullException e) {
      throw new LanguageModelUnavailableException("fila de chamadas ao modelo cheia", e);
    } catch (BusinessException e) {
      throw e;
    } catch (ExecutionException e) {
      throw unwrapLlm(e.getCause());
    } catch (Exception e) {
      throw unwrapLlm(e);
    }
  }

  private static RuntimeException unwrapLlm(Throwable e) {
    if (e instanceof BusinessException be) return be;
    return new LanguageModelUnavailableException("falha ao chamar o modelo de linguagem", e);
  }

  private CircuitBreaker cb(String name) {
    return circuitBreakers.circuitBreaker(name);
  }

  private static <T> T sourceCall(String what, Supplier<T> decorated) {
    try {
      return decorated.get();
    } catch (CallNotPermittedException e) {
      LOGGER.warn("disjuntor aberto para {} — chamada recusada sem tentar", what);
      throw new SourceUnavailableException("disjuntor aberto: " + what, e);
    } catch (RequestNotPermitted e) {
      throw new SourceUnavailableException("rate limiter esgotou a espera: " + what, e);
    } catch (BulkheadFullException e) {
      throw new SourceUnavailableException("bulkhead cheio: " + what, e);
    } catch (TransientFailure e) {
      throw new SourceUnavailableException(
          "falha ao acessar " + what + " depois das retentativas: " + e.getMessage(), e);
    }
  }
}
