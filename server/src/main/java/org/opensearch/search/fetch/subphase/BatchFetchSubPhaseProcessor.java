/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.search.fetch.subphase;

import org.opensearch.search.fetch.FetchContext;
import org.opensearch.search.fetch.FetchSubPhaseProcessor;

import java.io.IOException;

/**
 * Interface for fetch sub-phase processors that support batch processing.
 * 
 * @opensearch.api
 */
public interface BatchFetchSubPhaseProcessor extends FetchSubPhaseProcessor {
    
    /**
     * Process all hits in the fetch context as a batch.
     * This allows for efficient batch operations across multiple documents.
     * 
     * @param fetchContext The fetch context containing all hits
     * @throws IOException if an error occurs during processing
     */
    void processBatch(FetchContext fetchContext) throws IOException;
}