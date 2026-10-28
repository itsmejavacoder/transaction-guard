package com.transactionguard.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The clock is injected everywhere instead of calling {@link java.time.Instant#now()}
 * so that time-dependent behaviour (retry backoff) stays testable.
 */
@Configuration
public class TimeConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
