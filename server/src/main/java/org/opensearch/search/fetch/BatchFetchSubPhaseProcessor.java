/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.search.fetch;

import org.opensearch.search.fetch.FetchSubPhase.HitContext;

import java.io.IOException;
import java.util.List;

/**
 * Extension of {@link FetchSubPhaseProcessor} that supports batch processing
 * of multiple documents in a single operation.
 * 
 * This interface enables fetch sub-phases to process multiple documents
 * together, which is particularly useful for operations that benefit from
 * batching, such as ML-based highlighting or other computationally expensive
 * operations that can be optimized through batch processing.
 *
 * @opensearch.api
 */
public interface BatchFetchSubPhaseProcessor extends FetchSubPhaseProcessor {

    /**
     * Indicates whether this processor requires batch processing mode.
     * When true, the FetchPhase will collect all HitContexts before
     * calling processBatch instead of calling process for each hit.
     * 
     * @return true if batch processing is required, false otherwise
     */
    default boolean requiresBatchProcessing() {
        return false;
    }

    /**
     * Process multiple documents in a single batch operation.
     * This method is called once with all collected HitContexts
     * instead of calling process() for each individual hit.
     * 
     * @param hitContexts list of hit contexts to process in batch
     * @throws IOException if an error occurs during batch processing
     */
    void processBatch(List<HitContext> hitContexts) throws IOException;

    /**
     * Default implementation that throws UnsupportedOperationException.
     * Batch processors should not process individual hits when
     * requiresBatchProcessing() returns true.
     * 
     * @param hitContext the hit context to process
     * @throws UnsupportedOperationException always, as batch processors
     *         should use processBatch instead
     */
    @Override
    default void process(HitContext hitContext) throws IOException {
        if (requiresBatchProcessing()) {
            throw new UnsupportedOperationException(
                "Batch processor should not process individual hits. Use processBatch() instead."
            );
        }
        // For processors that don't require batch processing,
        // this method should be overridden
        throw new UnsupportedOperationException(
            "Either implement process() for single hit processing or enable batch processing"
        );
    }
}