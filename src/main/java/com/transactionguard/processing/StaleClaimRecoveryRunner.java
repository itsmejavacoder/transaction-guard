package com.transactionguard.processing;

import com.transactionguard.config.TransactionGuardProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Runs the stale-claim sweep once at startup, after the optional startup feed has
 * been ingested. A worker that died mid-cycle leaves records in {@code PROCESSING};
 * without this sweep they would remain stuck until an operator noticed the gap.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class StaleClaimRecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StaleClaimRecoveryRunner.class);

    private final TransactionRecoveryService recoveryService;
    private final TransactionGuardProperties properties;

    public StaleClaimRecoveryRunner(TransactionRecoveryService recoveryService,
                                    TransactionGuardProperties properties) {
        this.recoveryService = recoveryService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.recovery().enabled()) {
            return;
        }
        try {
            RecoveryReport report = recoveryService.recoverStaleClaims();
            if (report.touched() > 0) {
                log.warn("Startup stale-claim sweep recovered={} failed={}",
                        report.recovered(), report.failed());
            }
        } catch (RuntimeException ex) {
            log.error("Startup stale-claim sweep failed", ex);
        }
    }
}
