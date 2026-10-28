package com.transactionguard.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(info = @Info(
        title = "TransactionGuard - Idempotent Background Transaction Processing Service",
        version = "1.0.0",
        description = """
                Sequence-ordered, exactly-once transaction ingestion and background processing.

                Transactions are persisted first, then a scheduled worker claims them in stream
                order and applies each effect once. Duplicate deliveries are reported without a
                second effect, out-of-order records wait as PENDING_PREDECESSOR, transient
                settlement failures retry with backoff, and terminal failures are recoverable
                through an audited operator requeue.
                """))
public class OpenApiConfig {
}
