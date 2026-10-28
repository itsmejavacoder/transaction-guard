package com.transactionguard.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.transactionguard.config.TransactionGuardProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * Optionally ingests a JSON feed on startup ({@code transactionguard.ingestion.startup-file}).
 * This is what the demo profile uses to replay the assessment sample automatically.
 */
@Component
public class StartupFeedIngestionRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupFeedIngestionRunner.class);

    private final TransactionGuardProperties properties;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;
    private final TransactionFeedService feedService;

    public StartupFeedIngestionRunner(TransactionGuardProperties properties,
                                      ResourceLoader resourceLoader,
                                      ObjectMapper objectMapper,
                                      TransactionFeedService feedService) {
        this.properties = properties;
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
        this.feedService = feedService;
    }

    @Override
    public void run(ApplicationArguments args) {
        String location = properties.ingestion().startupFile();
        if (location == null || location.isBlank()) {
            return;
        }
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            log.warn("Startup feed {} does not exist; skipping ingestion", location);
            return;
        }
        try {
            FeedIngestionReport report = feedService.ingest(
                    new JsonFileTransactionSource(resource, objectMapper));
            log.info("Startup feed ingested: total={} received={} pending={} duplicate={} rejected={}",
                    report.totalCount(), report.receivedCount(), report.pendingCount(),
                    report.duplicateCount(), report.rejectedCount());
        } catch (FeedParseException ex) {
            log.error("Startup feed {} could not be read: {}", location, ex.getMessage());
        }
    }
}
