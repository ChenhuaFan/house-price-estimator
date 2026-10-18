package com.hsbc.market;

import com.hsbc.market.config.MarketProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Starts the property market Spring Boot application. */
@EnableScheduling
@EnableConfigurationProperties(MarketProperties.class)
@SpringBootApplication
public class MarketApplication {
  public static void main(String[] args) {
    SpringApplication.run(MarketApplication.class, args);
  }
}
