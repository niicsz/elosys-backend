package com.binitech.elosys.config;

import java.util.Arrays;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
  private final String[] allowedOrigins;

  public WebConfig(ElosysProperties properties) {
    this.allowedOrigins =
        Arrays.stream(properties.allowedOrigins().split(","))
            .map(String::strip)
            .filter(s -> !s.isEmpty())
            .toArray(String[]::new);
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry
        .addMapping("/api/**")
        .allowedOrigins(allowedOrigins)
        .allowedMethods("GET", "POST")
        .allowedHeaders("Content-Type", "X-Admin-Token");
  }
}
