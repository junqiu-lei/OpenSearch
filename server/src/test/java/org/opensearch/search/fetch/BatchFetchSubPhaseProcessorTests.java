/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.search.fetch;

import org.apache.lucene.index.LeafReaderContext;
import org.opensearch.search.fetch.FetchSubPhase.HitContext;
import org.opensearch.test.OpenSearchTestCase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests for BatchFetchSubPhaseProcessor interface
 */
public class BatchFetchSubPhaseProcessorTests extends OpenSearchTestCase {

    /**
     * Test that batch processor correctly implements the interface
     */
    public void testBatchProcessorInterface() throws IOException {
        AtomicInteger batchCount = new AtomicInteger(0);
        List<HitContext> collectedHits = new ArrayList<>();

        BatchFetchSubPhaseProcessor processor = new TestBatchProcessor(batchCount, collectedHits);

        // Verify that requiresBatchProcessing returns true
        assertTrue(processor.requiresBatchProcessing());

        // Create test hits list (without actual HitContext objects)
        List<HitContext> testHits = new ArrayList<>();
        // In real usage, these would be actual HitContext objects
        
        // Process batch
        processor.processBatch(testHits);

        // Verify batch was called
        assertEquals(1, batchCount.get());
    }

    /**
     * Test that process() throws exception when batch processing is required
     */
    public void testProcessThrowsExceptionForBatchProcessor() {
        BatchFetchSubPhaseProcessor processor = new TestBatchProcessor(null, null);

        // Verify requiresBatchProcessing returns true
        assertTrue(processor.requiresBatchProcessing());
        
        // Verify process() throws exception
        Exception exception = expectThrows(UnsupportedOperationException.class, () -> {
            processor.process(null);
        });
        
        assertTrue(exception.getMessage().contains("Batch processor should not process individual hits"));
    }

    /**
     * Test backward compatibility when requiresBatchProcessing returns false
     */
    public void testBackwardCompatibility() throws IOException {
        AtomicInteger processCount = new AtomicInteger(0);

        BatchFetchSubPhaseProcessor processor = new TestNonBatchProcessor(processCount);

        // Verify that requiresBatchProcessing returns false
        assertFalse(processor.requiresBatchProcessing());

        // Process individual hit should work
        processor.process(null);
        
        assertEquals(1, processCount.get());
    }

    /**
     * Test implementation of BatchFetchSubPhaseProcessor for testing
     */
    private static class TestBatchProcessor implements BatchFetchSubPhaseProcessor {
        private final AtomicInteger batchCount;
        private final List<HitContext> collectedHits;

        TestBatchProcessor(AtomicInteger batchCount, List<HitContext> collectedHits) {
            this.batchCount = batchCount;
            this.collectedHits = collectedHits;
        }

        @Override
        public void setNextReader(LeafReaderContext readerContext) {
            // No-op for test
        }

        @Override
        public boolean requiresBatchProcessing() {
            return true;
        }

        @Override
        public void processBatch(List<HitContext> hitContexts) {
            if (batchCount != null) {
                batchCount.incrementAndGet();
            }
            if (collectedHits != null) {
                collectedHits.addAll(hitContexts);
            }
        }
    }

    /**
     * Test implementation that doesn't require batch processing
     */
    private static class TestNonBatchProcessor implements BatchFetchSubPhaseProcessor {
        private final AtomicInteger processCount;

        TestNonBatchProcessor(AtomicInteger processCount) {
            this.processCount = processCount;
        }

        @Override
        public void setNextReader(LeafReaderContext readerContext) {
            // No-op for test
        }

        @Override
        public boolean requiresBatchProcessing() {
            return false;
        }

        @Override
        public void processBatch(List<HitContext> hitContexts) {
            fail("processBatch should not be called when requiresBatchProcessing is false");
        }

        @Override
        public void process(HitContext hitContext) {
            processCount.incrementAndGet();
        }
    }
}