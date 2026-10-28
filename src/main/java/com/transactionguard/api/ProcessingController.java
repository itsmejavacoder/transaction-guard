package com.transactionguard.api;

import com.transactionguard.processing.ProcessingCycleReport;
import com.transactionguard.processing.ProcessingSummary;
import com.transactionguard.processing.ProcessingSummaryService;
import com.transactionguard.processing.RecoveryReport;
import com.transactionguard.processing.SequenceGap;
import com.transactionguard.processing.SequenceGate;
import com.transactionguard.processing.TransactionProcessingWorker;
import com.transactionguard.processing.TransactionRecoveryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Operational endpoints: the processing report, current sequence gaps, and a manual
 * worker trigger (useful for demos and for platforms without a scheduler).
 */
@RestController
@RequestMapping("/api/v1/processing")
public class ProcessingController {

    private final ProcessingSummaryService summaryService;
    private final TransactionProcessingWorker worker;
    private final SequenceGate sequenceGate;
    private final TransactionRecoveryService recoveryService;

    public ProcessingController(ProcessingSummaryService summaryService,
                                TransactionProcessingWorker worker,
                                SequenceGate sequenceGate,
                                TransactionRecoveryService recoveryService) {
        this.summaryService = summaryService;
        this.worker = worker;
        this.sequenceGate = sequenceGate;
        this.recoveryService = recoveryService;
    }

    @GetMapping("/summary")
    public ProcessingSummary summary() {
        return summaryService.summarize();
    }

    @GetMapping("/sequence-gaps")
    public List<SequenceGap> sequenceGaps() {
        return sequenceGate.currentGaps();
    }

    /**
     * Runs one processing cycle synchronously and returns what happened. Safe to call
     * repeatedly; the internal guard prevents overlapping cycles.
     */
    @PostMapping("/cycles")
    public ResponseEntity<ProcessingCycleReport> runCycle() {
        ProcessingCycleReport report = worker.runCycle();
        return ResponseEntity.ok(report);
    }

    /**
     * Runs the stale-claim sweep now instead of waiting for the scheduled interval.
     * Useful after a forced restart or as a manual runbook step.
     */
    @PostMapping("/recover-stale")
    public RecoveryReport recoverStaleClaims() {
        return recoveryService.recoverStaleClaims();
    }
}
