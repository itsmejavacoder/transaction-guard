package com.transactionguard.api;

import com.transactionguard.processing.ProcessingCycleReport;
import com.transactionguard.processing.ProcessingSummary;
import com.transactionguard.processing.ProcessingSummaryService;
import com.transactionguard.processing.SequenceGap;
import com.transactionguard.processing.SequenceGate;
import com.transactionguard.processing.TransactionProcessingWorker;
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

    public ProcessingController(ProcessingSummaryService summaryService,
                                TransactionProcessingWorker worker,
                                SequenceGate sequenceGate) {
        this.summaryService = summaryService;
        this.worker = worker;
        this.sequenceGate = sequenceGate;
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
}
