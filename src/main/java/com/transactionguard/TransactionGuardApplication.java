package com.transactionguard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * TransactionGuard - an idempotent background transaction processing service.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class TransactionGuardApplication {

    public static void main(String[] args) {
        SpringApplication.run(TransactionGuardApplication.class, args);
    }
}
