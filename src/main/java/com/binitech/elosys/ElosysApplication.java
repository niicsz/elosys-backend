package com.binitech.elosys;

import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ElosysApplication {
  public static void main(String[] args) {
    boolean cli = Arrays.stream(args).anyMatch(a -> !a.startsWith("--"));
    if (!cli) {
      SpringApplication.run(ElosysApplication.class, args);
      return;
    }
    ConfigurableApplicationContext context =
        new SpringApplicationBuilder(ElosysApplication.class)
            .web(WebApplicationType.NONE)
            .run(args);
    System.exit(SpringApplication.exit(context));
  }
}
