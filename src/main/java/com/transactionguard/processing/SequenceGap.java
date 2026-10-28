package com.transactionguard.processing;

import java.util.List;

/**
 * A hole detected in an ordered stream: the cursor is waiting for
 * {@code nextExpectedSequence} but the earliest deferred record is further ahead.
 */
public record SequenceGap(String streamId,
                          long nextExpectedSequence,
                          long firstPendingSequence,
                          long missingCount,
                          List<Long> missingSequences) {
}
