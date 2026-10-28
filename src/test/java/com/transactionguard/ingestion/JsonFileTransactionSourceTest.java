package com.transactionguard.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.transactionguard.domain.TransactionRequest;
import com.transactionguard.domain.TransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonFileTransactionSourceTest {

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();

    @Test
    void readsTheAssessmentSample() {
        Resource resource = new ClassPathResource("samples/sample-transactions.json");

        List<TransactionRequest> requests = new JsonFileTransactionSource(resource, objectMapper).readAll();

        assertThat(requests).hasSize(6);
        assertThat(requests.get(0).transactionId()).isEqualTo("TXN-2002");
        assertThat(requests.get(0).sequenceNumber()).isEqualTo(2L);
        assertThat(requests.get(0).transactionType()).isEqualTo(TransactionType.DEBIT);
        assertThat(requests.get(1).transactionId()).isEqualTo("TXN-2001");
        assertThat(requests.get(4).amount()).isEqualByComparingTo("-10.00");
    }

    @Test
    void acceptsATopLevelArray(@TempDir Path tempDir) throws Exception {
        Path feed = tempDir.resolve("feed.json");
        Files.writeString(feed, """
                [
                  {"requestId":"REQ-1","transactionId":"TXN-1","accountId":"ACC-1",
                   "transactionType":"CREDIT","amount":10.00,"currency":"INR","sequenceNumber":1}
                ]
                """);

        List<TransactionRequest> requests = new JsonFileTransactionSource(
                new FileSystemResource(feed), objectMapper).readAll();

        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).transactionId()).isEqualTo("TXN-1");
    }

    @Test
    void rejectsAnObjectWithoutTransactionsArray(@TempDir Path tempDir) throws Exception {
        Path feed = tempDir.resolve("feed.json");
        Files.writeString(feed, "{\"unexpected\":true}");

        assertThatThrownBy(() -> new JsonFileTransactionSource(new FileSystemResource(feed), objectMapper).readAll())
                .isInstanceOf(FeedParseException.class)
                .hasMessageContaining("transactions");
    }

    @Test
    void reportsTheOffendingRecordIndex(@TempDir Path tempDir) throws Exception {
        Path feed = tempDir.resolve("feed.json");
        Files.writeString(feed, """
                [{"requestId":"REQ-1","transactionId":"TXN-1","accountId":"ACC-1",
                   "transactionType":"TRANSFER","amount":10.00,"currency":"INR","sequenceNumber":1}]
                """);

        assertThatThrownBy(() -> new JsonFileTransactionSource(new FileSystemResource(feed), objectMapper).readAll())
                .isInstanceOf(FeedParseException.class)
                .hasMessageContaining("index 0");
    }

    @Test
    void rejectsAMissingResource() {
        Resource missing = new FileSystemResource("does/not/exist.json");

        assertThatThrownBy(() -> new JsonFileTransactionSource(missing, objectMapper).readAll())
                .isInstanceOf(FeedParseException.class)
                .hasMessageContaining("not found");
    }
}
