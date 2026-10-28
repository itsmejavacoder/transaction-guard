package com.transactionguard.processing;

/**
 * Contract for the downstream settlement call. In production this would be an HTTP
 * or messaging adapter; here it is a simulated, idempotent gateway.
 */
public interface SettlementGateway {

    /**
     * @throws TransientSettlementException when the call failed in a way that is worth retrying
     */
    void settle(SettlementInstruction instruction);
}
