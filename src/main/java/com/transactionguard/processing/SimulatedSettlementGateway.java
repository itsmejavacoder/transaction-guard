package com.transactionguard.processing;

import com.transactionguard.config.TransactionGuardProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-process stand-in for the downstream settlement system.
 *
 * <p>Two properties are modelled because they are the ones that matter for retry
 * safety:</p>
 * <ul>
 *   <li><b>Idempotent by effect key</b>: settling the same key twice has one effect.</li>
 *   <li><b>Transient failures</b>: configured per transaction, so a retry can be
 *       demonstrated deterministically.</li>
 * </ul>
 */
@Component
public class SimulatedSettlementGateway implements SettlementGateway {

    private final Map<String, Integer> configuredFailures;
    private final Map<String, AtomicInteger> remainingFailures = new ConcurrentHashMap<>();
    private final Set<String> settledEffectKeys = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicInteger> settlementAttempts = new ConcurrentHashMap<>();

    public SimulatedSettlementGateway(TransactionGuardProperties properties) {
        this.configuredFailures = properties.simulation().transientFailures();
        this.remainingFailures.putAll(toCounters(configuredFailures));
    }

    @Override
    public void settle(SettlementInstruction instruction) {
        settlementAttempts.computeIfAbsent(instruction.effectKey(), key -> new AtomicInteger()).incrementAndGet();

        AtomicInteger remaining = remainingFailures.get(instruction.effectKey());
        if (remaining != null && remaining.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
            throw new TransientSettlementException("SIMULATED_GATEWAY_TIMEOUT",
                    "Simulated transient settlement timeout for " + instruction.effectKey());
        }

        settledEffectKeys.add(instruction.effectKey());
    }

    /**
     * Effects are counted per effect key, so an idempotent retry does not add a second effect.
     */
    public int effectCount(String effectKey) {
        return settledEffectKeys.contains(effectKey) ? 1 : 0;
    }

    public int attemptCount(String effectKey) {
        AtomicInteger attempts = settlementAttempts.get(effectKey);
        return attempts == null ? 0 : attempts.get();
    }

    public int totalEffects() {
        return settledEffectKeys.size();
    }

    public int remainingFailures(String effectKey) {
        AtomicInteger remaining = remainingFailures.get(effectKey);
        return remaining == null ? 0 : remaining.get();
    }

    /**
     * Test/demo hook: make the next {@code attempts} settlement calls for the given
     * transaction fail.
     */
    public void failNext(String effectKey, int attempts) {
        remainingFailures.put(effectKey, new AtomicInteger(Math.max(0, attempts)));
    }

    /**
     * Restores configured fault injection and clears all call history.
     */
    public void reset() {
        remainingFailures.clear();
        remainingFailures.putAll(toCounters(configuredFailures));
        settledEffectKeys.clear();
        settlementAttempts.clear();
    }

    private static Map<String, AtomicInteger> toCounters(Map<String, Integer> source) {
        Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();
        source.forEach((key, value) -> counters.put(key, new AtomicInteger(Math.max(0, value))));
        return counters;
    }
}
