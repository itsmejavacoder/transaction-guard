package com.transactionguard.api;

import com.transactionguard.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guards the generated API contract. If an endpoint is renamed or a controller loses
 * its mapping, the published OpenAPI document changes and this test fails.
 */
@AutoConfigureMockMvc
class OpenApiIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("the OpenAPI document is served with every public API path")
    void openApiDocumentIsExposedWithEveryApiPath() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title")
                        .value("TransactionGuard - Idempotent Background Transaction Processing Service"))
                .andExpect(jsonPath("$.paths['/api/v1/transactions']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/transactions/batch']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/transactions/{transactionId}']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/transactions/{transactionId}/requeue']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/processing/summary']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/processing/sequence-gaps']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/processing/cycles']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/processing/recover-stale']").exists());
    }

    @Test
    @DisplayName("Swagger UI is served")
    void swaggerUiIsServed() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }
}
