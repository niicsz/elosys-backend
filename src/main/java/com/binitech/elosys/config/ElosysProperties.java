package com.binitech.elosys.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "elosys")
public record ElosysProperties(
    String tmpDir,
    String reportsDir,
    String adminToken,
    String allowedOrigins,
    Cache cache,
    Download download,
    Llm llm,
    Apify apify) {

  public record Cache(Duration defaultTtl, String keyPrefix) {}

  public record Download(
      String curlImpersonatePath, Duration connectTimeout, Duration readTimeout) {}

  public record Llm(String apiKey, String model, Duration timeout, long maxTokens) {}

  public record Apify(String token, String actor, String baseUrl) {}
}
