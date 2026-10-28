package com.transactionguard.api;

import com.transactionguard.processing.ProcessingCycleReport;
import com.transactionguard.processing.ProcessingSummary;
import com.transactionguard.processing.ProcessingSummaryService;
import com.transactionguard.processing.RecoveryReport;
import com.transactionguard.processing.SequenceGap;
import com.transactionguard.processing.SequenceGate;
import com.transactionguard.processing.TransactionProcessingWorker;
import com.transactionguard.processing.TransactionRecoveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Processing", description = "Operational reports, gap visibility and manual worker controls")
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

    @Operation(summary = "Get processing summary", description = "Status counts, duplicate and retry pressure, balances, ledger totals and gaps.")
    @GetMapping("/summary")
    public ProcessingSummary summary() {
        return summaryService.summarize();
    }

    @Operation(summary = "Get sequence gaps", description = "Streams currently blocked by a hole in their sequence.")
    @GetMapping("/sequence-gaps")
    public List<SequenceGap> sequenceGaps() {
        return sequenceGate.currentGaps();
    }

    /**
     * Runs one processing cycle synchronously and returns what happened. Safe to call
     * repeatedly; the internal guard prevents overlapping cycles.
     */
    @Operation(summary = "Run one worker cycle", description = "Synchronously runs a cycle and returns what happened. Overlapping cycles return alreadyRunning=true.")
    @PostMapping("/cycles")
    public ResponseEntity<ProcessingCycleReport> runCycle() {
        ProcessingCycleReport report = worker.runCycle();
        return ResponseEntity.ok(report);
    }

    /**
     * Runs the stale-claim sweep now instead of waiting for the scheduled interval.
     * Useful after a forced restart or as a manual runbook step.
     */
    @Operation(summary = "Recover stale claims", description = "Runs the abandoned-claim sweep now and returns recovered and failed counts.")
    @PostMapping("/recover-stale")
    public RecoveryReport recoverStaleClaims() {
        return recoveryService.recoverStaleClaims();
    }
}
