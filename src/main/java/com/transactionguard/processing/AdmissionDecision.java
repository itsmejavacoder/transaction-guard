package com.transactionguard.processing;

import com.transactionguard.domain.TransactionStatus;

/**
 * Result of the sequence admission step.
 *
 * @param status   status the record should have
 * @param recorded whether the caller should persist the record
 * @param code     stable reason code when the record could not be admitted
 * @param detail   human-readable explanation
 */
public record AdmissionDecision(TransactionStatus status, boolean recorded, String code, String detail) {

    public static AdmissionDecision accepted(TransactionStatus status, String detail) {
        return new AdmissionDecision(status, true, null, detail);
    }

    public static AdmissionDecision rejected(String code, String detail) {
        return new AdmissionDecision(TransactionStatus.REJECTED, false, code, detail);
    }
}
