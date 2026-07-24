package com.urlshortener;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the URL Shortener system.
 *
 * Phase 1: Core shortening + redirect + JWT auth (Spring Boot, PostgreSQL)
 * Phase 2: Analytics, custom aliases, expiration
 * Phase 3: Redis caching layer
 * Phase 4: Kafka event streaming, rate limiting, observability
 */
@SpringBootApplication
@EnableCaching
@EnableAsync
@EnableScheduling
public class UrlShortenerApplication {

    public static void main(String[] args) {
        SpringApplication.run(UrlShortenerApplication.class, args);
    }
}
