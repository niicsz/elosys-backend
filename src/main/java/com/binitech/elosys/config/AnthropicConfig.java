package com.binitech.elosys.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AnthropicConfig {

  @Bean(destroyMethod = "close")
  public AnthropicClient anthropicClient(ElosysProperties properties) {
    ElosysProperties.Llm llm = properties.llm();
    return AnthropicOkHttpClient.builder()
        .apiKey(llm.apiKey() == null || llm.apiKey().isBlank() ? "não-configurada" : llm.apiKey())
        .timeout(llm.timeout())
        .maxRetries(0)
        .build();
  }
}
