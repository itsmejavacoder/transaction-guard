package com.transactionguard.observability;

import org.springframework.stereotype.Component;

/**
 * Masks account references before they reach log files. The full account id never
 * appears in logs; operators can still correlate by the first/last characters.
 */
@Component
public class SensitiveDataMasker {

    public String maskAccountId(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return "****";
        }
        String trimmed = accountId.trim();
        if (trimmed.length() <= 4) {
            return "****";
        }
        String prefix = trimmed.substring(0, Math.min(4, trimmed.length() - 2));
        String suffix = trimmed.substring(trimmed.length() - 2);
        return prefix + "***" + suffix;
    }
}
