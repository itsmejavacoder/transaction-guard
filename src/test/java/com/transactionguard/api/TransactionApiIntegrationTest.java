package com.transactionguard.api;

import com.transactionguard.domain.TransactionStatus;
import com.transactionguard.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.transactionguard.support.TransactionFixtures.credit;
import static com.transactionguard.support.TransactionFixtures.debit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class TransactionApiIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void acceptsValidTransactionWith202() throws Exception {
        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(credit("REQ-1", "TXN-1", "ACC-1", "100.00", 1))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.transactionId").value("TXN-1"))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.recorded").value(true));
    }

    @Test
    void replayReturns200AndOriginalState() throws Exception {
        String body = json(credit("REQ-1", "TXN-1", "ACC-1", "100.00", 1));
        mockMvc.perform(post("/api/v1/transactions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/v1/transactions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.status").value("DUPLICATE"));

        assertThat(transactions.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a well-formed but invalid record is 422 and stored as REJECTED")
    void invalidBusinessRecordReturns422() throws Exception {
        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(debit("REQ-1", "TXN-1", "ACC-1", "-10.00", 1))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.validationIssues[0].code").value("AMOUNT_NOT_POSITIVE"));

        assertThat(recordOf("TXN-1").getStatus()).isEqualTo(TransactionStatus.REJECTED);
    }

    @Test
    @DisplayName("a request without identity is 400 and is not persisted")
    void malformedRequestReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-1","accountId":"ACC-1","transactionType":"CREDIT",
                                 "amount":10.00,"currency":"INR","sequenceNumber":1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.recorded").value(false))
                .andExpect(jsonPath("$.validationIssues[0].code").value("TRANSACTION_ID_REQUIRED"));

        assertThat(transactions.count()).isZero();
    }

    @Test
    @DisplayName("a new transaction on a consumed sequence is 409 and not persisted")
    void sequenceConflictReturns409() throws Exception {
        ingestOne(credit("REQ-1", "TXN-1", "ACC-1", "100.00", 1));

        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(credit("REQ-2", "TXN-2", "ACC-1", "10.00", 1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.recorded").value(false))
                .andExpect(jsonPath("$.validationIssues[0].code").value("SEQUENCE_CONFLICT"));

        assertThat(transactions.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a sequence the stream already passed is 409 and not persisted")
    void staleSequenceReturns409() throws Exception {
        ingestOne(credit("REQ-1", "TXN-1", "ACC-1", "100.00", 1));
        drain();

        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(credit("REQ-2", "TXN-2", "ACC-2", "10.00", 1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.recorded").value(false))
                .andExpect(jsonPath("$.validationIssues[0].code").value("STALE_SEQUENCE"));

        assertThat(transactions.findByTransactionId("TXN-2")).isEmpty();
    }

    @Test
    void malformedJsonReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
    }

    @Test
    @DisplayName("batch ingestion isolates a bad record and returns 207")
    void batchIsPartialSuccess() throws Exception {
        TransactionBatchRequest batch = new TransactionBatchRequest(List.of(
                credit("REQ-1", "TXN-1", "ACC-1", "100.00", 1),
                debit("REQ-2", "TXN-2", "ACC-1", "40.00", 2),
                debit("REQ-3", "TXN-3", "ACC-1", "-10.00", 3)));

        mockMvc.perform(post("/api/v1/transactions/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batch)))
                .andExpect(status().isMultiStatus())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.countsByStatus.RECEIVED").value(1))
                .andExpect(jsonPath("$.countsByStatus.PENDING_PREDECESSOR").value(1))
                .andExpect(jsonPath("$.countsByStatus.REJECTED").value(1))
                .andExpect(jsonPath("$.results[0].transactionId").value("TXN-1"))
                .andExpect(jsonPath("$.results[2].status").value("REJECTED"));
    }

    @Test
    void emptyBatchReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/transactions/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactions\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].code").value("BATCH_EMPTY"));
    }

    @Test
    void transactionDetailIncludesLedgerAndHistory() throws Exception {
        ingestOne(credit("REQ-1", "TXN-1", "ACC-1", "100.00", 1));
        drain();

        mockMvc.perform(get("/api/v1/transactions/TXN-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSED"))
                .andExpect(jsonPath("$.effectApplied").value(true))
                .andExpect(jsonPath("$.ledgerEntry").exists())
                .andExpect(jsonPath("$.ledgerEntry.balanceAfter").exists())
                .andExpect(jsonPath("$.history[0].reason").value("INGESTED_READY"));
    }

    @Test
    void unknownTransactionReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/transactions/DOES-NOT-EXIST"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void listFiltersByStatus() throws Exception {
        ingest(credit("REQ-1", "TXN-1", "ACC-1", "100.00", 1),
                credit("REQ-2", "TXN-2", "ACC-1", "40.00", 2));

        mockMvc.perform(get("/api/v1/transactions").param("status", "RECEIVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].transactionId").value("TXN-1"));
    }

    @Test
    void invalidQueryParameterReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/transactions").param("status", "NOT_A_STATUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    void summaryReportsCountsAndGaps() throws Exception {
        ingest(credit("REQ-1", "TXN-1", "ACC-1", "100.00", 1),
                credit("REQ-3", "TXN-3", "ACC-1", "25.00", 3));

        mockMvc.perform(get("/api/v1/processing/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTransactions").value(2))
                .andExpect(jsonPath("$.countsByStatus.RECEIVED").value(1))
                .andExpect(jsonPath("$.countsByStatus.PENDING_PREDECESSOR").value(1));

        mockMvc.perform(get("/api/v1/processing/sequence-gaps"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].missingSequences[0]").value(2));
    }

    @Test
    void manualCycleProcessesReadyRecords() throws Exception {
        ingestOne(credit("REQ-1", "TXN-1", "ACC-1", "100.00", 1));

        mockMvc.perform(post("/api/v1/processing/cycles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimedCount").value(1))
                .andExpect(jsonPath("$.processedCount").value(1));

        mockMvc.perform(get("/api/v1/transactions/TXN-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSED"));
    }

    @Test
    @DisplayName("a FAILED transaction can be requeued through the API")
    void requeueFailedTransactionReturns202() throws Exception {
        gateway.failNext("TXN-1", 99);
        ingestOne(credit("REQ-1", "TXN-1", "ACC-1", "100.00", 1));
        drain();
        assertThat(recordOf("TXN-1").getStatus()).isEqualTo(TransactionStatus.FAILED);

        mockMvc.perform(post("/api/v1/transactions/TXN-1/requeue"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.transactionId").value("TXN-1"))
                .andExpect(jsonPath("$.status").value("RETRY_PENDING"))
                .andExpect(jsonPath("$.attemptCount").value(0))
                .andExpect(jsonPath("$.history[?(@.reason == 'MANUAL_REQUEUE')].reason")
                        .value(org.hamcrest.Matchers.hasItem("MANUAL_REQUEUE")));
    }

    @Test
    void requeueOfNonFailedTransactionReturns409() throws Exception {
        ingestOne(credit("REQ-1", "TXN-1", "ACC-1", "100.00", 1));

        mockMvc.perform(post("/api/v1/transactions/TXN-1/requeue"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_REQUEUEABLE"))
                .andExpect(jsonPath("$.message").value(containsString("RECEIVED")));
    }

    @Test
    void requeueOfUnknownTransactionReturns404() throws Exception {
        mockMvc.perform(post("/api/v1/transactions/DOES-NOT-EXIST/requeue"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("the manual stale-claim sweep returns what it recovered")
    void recoverStaleClaimsEndpointReturnsReport() throws Exception {
        ingestOne(credit("REQ-1", "TXN-1", "ACC-1", "100.00", 1));
        Long recordId = recordOf("TXN-1").getId();
        claim(recordId, Instant.now().minus(Duration.ofMinutes(10)), TransactionStatus.RECEIVED);

        mockMvc.perform(post("/api/v1/processing/recover-stale"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recovered").value(1))
                .andExpect(jsonPath("$.failed").value(0));

        assertThat(recordOf("TXN-1").getStatus()).isEqualTo(TransactionStatus.RETRY_PENDING);
    }

    @Test
    void echoesCorrelationId() throws Exception {
        mockMvc.perform(get("/api/v1/processing/summary").header("X-Request-Id", "corr-123"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "corr-123"));
    }

    @Test
    void healthEndpointIsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
