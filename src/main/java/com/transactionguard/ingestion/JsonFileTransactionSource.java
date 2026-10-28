package com.transactionguard.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.transactionguard.domain.TransactionRequest;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a JSON feed. Accepts either a top-level array or an object containing a
 * {@code transactions} array, which is the shape used by
 * {@code samples/sample-transactions.json}.
 */
public class JsonFileTransactionSource implements TransactionSource {

    private final Resource resource;
    private final ObjectMapper objectMapper;

    public JsonFileTransactionSource(Resource resource, ObjectMapper objectMapper) {
        this.resource = resource;
        this.objectMapper = objectMapper;
    }

    @Override
    public String description() {
        return resource.getDescription();
    }

    @Override
    public List<TransactionRequest> readAll() {
        if (!resource.exists()) {
            throw new FeedParseException("Feed not found: " + resource.getDescription());
        }
        try (InputStream input = resource.getInputStream()) {
            JsonNode root = objectMapper.readTree(input);
            JsonNode array = root.isArray() ? root : root.path("transactions");
            if (!array.isArray()) {
                throw new FeedParseException(
                        "Feed must be a JSON array or an object with a 'transactions' array: "
                                + resource.getDescription());
            }
            List<TransactionRequest> requests = new ArrayList<>(array.size());
            int index = 0;
            for (JsonNode node : array) {
                try {
                    requests.add(objectMapper.treeToValue(node, TransactionRequest.class));
                } catch (IOException ex) {
                    throw new FeedParseException("Invalid transaction at index " + index + ": " + ex.getMessage(), ex);
                }
                index++;
            }
            return List.copyOf(requests);
        } catch (IOException ex) {
            throw new FeedParseException("Unable to read feed " + resource.getDescription() + ": " + ex.getMessage(), ex);
        }
    }
}
