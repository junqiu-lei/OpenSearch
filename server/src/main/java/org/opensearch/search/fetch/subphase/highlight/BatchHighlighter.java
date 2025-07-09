/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.search.fetch.subphase.highlight;

import org.opensearch.core.common.io.stream.Writeable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Interface for highlighters that support batch processing of multiple documents.
 * 
 * @opensearch.api
 */
public interface BatchHighlighter extends Highlighter {
    
    Logger logger = LogManager.getLogger(BatchHighlighter.class);
    
    /**
     * Indicates whether this highlighter supports batch highlighting.
     * 
     * @return true if batch highlighting is supported
     */
    default boolean supportsBatchHighlighting() {
        System.out.println("\n\n========================================");
        System.out.println("BATCH HIGHLIGHTER INTERFACE VERIFICATION");
        System.out.println("supportsBatchHighlighting() called");
        System.out.println("Custom OpenSearch Core is ACTIVE!");
        System.out.println("========================================\n");
        logger.info("BatchHighlighter.supportsBatchHighlighting() called - Custom Core Active");
        return false;
    }
    
    /**
     * Highlights multiple field contexts in a single batch operation.
     * This method allows for efficient processing of multiple documents at once.
     * 
     * @param contexts List of field contexts to highlight
     * @return Map of field context to highlight field results
     * @throws IOException if an error occurs during highlighting
     */
    default Map<FieldHighlightContext, HighlightField> batchHighlight(List<FieldHighlightContext> contexts) throws IOException {
        logger.info("\n=== BATCH HIGHLIGHTER: batchHighlight() called ===");
        logger.info("=== Processing {} contexts in batch mode ===", contexts.size());
        logger.info("=== This confirms custom OpenSearch Core is running! ===");
        // Default implementation falls back to individual highlighting
        Map<FieldHighlightContext, HighlightField> results = new HashMap<>();
        for (FieldHighlightContext context : contexts) {
            HighlightField field = highlight(context);
            if (field != null) {
                results.put(context, field);
            }
        }
        return results;
    }
}