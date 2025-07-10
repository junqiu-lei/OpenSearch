/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.search.fetch.subphase.highlight;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.Query;
import org.opensearch.common.regex.Regex;
import org.opensearch.index.mapper.KeywordFieldMapper;
import org.opensearch.index.mapper.MappedFieldType;
import org.opensearch.index.mapper.MatchOnlyTextFieldMapper;
import org.opensearch.index.mapper.SourceFieldMapper;
import org.opensearch.index.mapper.TextFieldMapper;
import org.opensearch.search.fetch.BatchFetchSubPhaseProcessor;
import org.opensearch.search.fetch.FetchContext;
import org.opensearch.search.fetch.FetchSubPhase;
import org.opensearch.search.fetch.FetchSubPhaseProcessor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Highlight Phase of the search request.
 *
 * @opensearch.internal
 */
public class HighlightPhase implements FetchSubPhase {
    private static final Logger logger = LogManager.getLogger(HighlightPhase.class);

    private final Map<String, Highlighter> highlighters;

    public HighlightPhase(Map<String, Highlighter> highlighters) {
        this.highlighters = highlighters;
    }

    @Override
    public FetchSubPhaseProcessor getProcessor(FetchContext context) {
        if (context.highlight() == null) {
            return null;
        }

        return getProcessor(context, context.highlight(), context.parsedQuery().query());
    }

    public FetchSubPhaseProcessor getProcessor(FetchContext context, SearchHighlightContext highlightContext, Query query) {
        Map<String, Object> sharedCache = new HashMap<>();
        Map<String, Function<HitContext, FieldHighlightContext>> contextBuilders = contextBuilders(
            context,
            highlightContext,
            query,
            sharedCache
        );

        // Check if any highlighter supports batch processing
        if (hasBatchHighlighter(highlightContext)) {
            logger.info("Batch highlighting enabled - using BatchHighlightProcessor");
            return new BatchHighlightProcessor(contextBuilders);
        } else {
            logger.info("No batch highlighters found - using standard processing");
        }

        return new FetchSubPhaseProcessor() {
            @Override
            public void setNextReader(LeafReaderContext readerContext) {

            }

            @Override
            public void process(HitContext hitContext) throws IOException {
                Map<String, HighlightField> highlightFields = new HashMap<>();
                // Group contexts by highlighter
                Map<Highlighter, List<FieldHighlightContext>> groupedContexts = new HashMap<>();
                for (String field : contextBuilders.keySet()) {
                    FieldHighlightContext fieldContext = contextBuilders.get(field).apply(hitContext);
                    Highlighter highlighter = getHighlighter(fieldContext.field);
                    groupedContexts.computeIfAbsent(highlighter, k -> new ArrayList<>()).add(fieldContext);
                }

                for (Map.Entry<Highlighter, List<FieldHighlightContext>> entry : groupedContexts.entrySet()) {
                    Highlighter highlighter = entry.getKey();
                    List<FieldHighlightContext> contexts = entry.getValue();
                    if (highlighter instanceof BatchHighlighter && ((BatchHighlighter) highlighter).supportsBatchHighlighting()) {
                        // Batch processing logic
                        BatchHighlighter batchHighlighter = (BatchHighlighter) highlighter;
                        Map<FieldHighlightContext, HighlightField> batchResults = batchHighlighter.batchHighlight(contexts);
                        for (Map.Entry<FieldHighlightContext, HighlightField> resultEntry : batchResults.entrySet()) {
                            String fieldName = resultEntry.getKey().fieldName;
                            HighlightField highlightField = resultEntry.getValue();
                            highlightFields.put(fieldName, new HighlightField(fieldName, highlightField.fragments()));
                        }
                    } else {
                        // Fallback to individual highlighting
                        for (FieldHighlightContext context : contexts) {
                            HighlightField highlightField = highlighter.highlight(context);
                            if (highlightField != null) {
                                highlightFields.put(context.fieldName, new HighlightField(context.fieldName, highlightField.fragments()));
                            }
                        }
                    }
                }
                hitContext.hit().highlightFields(highlightFields);
            }
        };
    }

    private Highlighter getHighlighter(SearchHighlightContext.Field field) {
        String highlighterType = field.fieldOptions().highlighterType();
        if (highlighterType == null) {
            highlighterType = "unified";
        }
        Highlighter highlighter = highlighters.get(highlighterType);
        if (highlighter == null) {
            throw new IllegalArgumentException("unknown highlighter type [" + highlighterType + "] for the field [" + field.field() + "]");
        }
        return highlighter;
    }

    private Map<String, Function<HitContext, FieldHighlightContext>> contextBuilders(
        FetchContext context,
        SearchHighlightContext highlightContext,
        Query query,
        Map<String, Object> sharedCache
    ) {
        Map<String, Function<HitContext, FieldHighlightContext>> builders = new LinkedHashMap<>();
        for (SearchHighlightContext.Field field : highlightContext.fields()) {
            Highlighter highlighter = getHighlighter(field);
            Collection<String> fieldNamesToHighlight;
            if (Regex.isSimpleMatchPattern(field.field())) {
                fieldNamesToHighlight = context.mapperService().simpleMatchToFullName(field.field());
            } else {
                fieldNamesToHighlight = Collections.singletonList(field.field());
            }

            if (highlightContext.forceSource(field)) {
                SourceFieldMapper sourceFieldMapper = context.mapperService().documentMapper().sourceMapper();
                if (sourceFieldMapper.enabled() == false) {
                    throw new IllegalArgumentException("source is forced for fields " + fieldNamesToHighlight + " but _source is disabled");
                }
            }

            boolean fieldNameContainsWildcards = field.field().contains("*");
            for (String fieldName : fieldNamesToHighlight) {
                MappedFieldType fieldType = context.mapperService().fieldType(fieldName);
                if (fieldType == null) {
                    fieldType = context.getQueryShardContext().resolveDerivedFieldType(fieldName);
                }
                if (fieldType == null) {
                    continue;
                }

                // We should prevent highlighting if a field is anything but a text, match_only_text
                // or keyword field.
                // However, someone might implement a custom field type that has text and still want to
                // highlight on that. We cannot know in advance if the highlighter will be able to
                // highlight such a field and so we do the following:
                // If the field is only highlighted because the field matches a wildcard we assume
                // it was a mistake and do not process it.
                // If the field was explicitly given we assume that whoever issued the query knew
                // what they were doing and try to highlight anyway.
                if (fieldNameContainsWildcards) {
                    if (fieldType.typeName().equals(TextFieldMapper.CONTENT_TYPE) == false
                        && fieldType.typeName().equals(KeywordFieldMapper.CONTENT_TYPE) == false
                        && fieldType.typeName().equals(MatchOnlyTextFieldMapper.CONTENT_TYPE) == false) {
                        continue;
                    }
                    if (highlighter.canHighlight(fieldType) == false) {
                        continue;
                    }
                }

                Query highlightQuery = field.fieldOptions().highlightQuery();

                boolean forceSource = highlightContext.forceSource(field);
                MappedFieldType finalFieldType = fieldType;
                builders.put(
                    fieldName,
                    hc -> new FieldHighlightContext(
                        finalFieldType.name(),
                        field,
                        finalFieldType,
                        context,
                        hc,
                        highlightQuery == null ? query : highlightQuery,
                        forceSource,
                        sharedCache
                    )
                );
            }
        }
        return builders;
    }

    private boolean hasBatchHighlighter(SearchHighlightContext highlightContext) {
        for (SearchHighlightContext.Field field : highlightContext.fields()) {
            Highlighter highlighter = getHighlighter(field);
            if (highlighter instanceof BatchHighlighter && ((BatchHighlighter) highlighter).supportsBatchHighlighting()) {
                return true;
            }
        }
        return false;
    }

    private class BatchHighlightProcessor implements BatchFetchSubPhaseProcessor {
        private final Map<String, Function<HitContext, FieldHighlightContext>> contextBuilders;
        private final Map<HitContext, Map<Highlighter, List<FieldHighlightContext>>> collectedContexts;

        BatchHighlightProcessor(Map<String, Function<HitContext, FieldHighlightContext>> contextBuilders) {
            this.contextBuilders = contextBuilders;
            this.collectedContexts = new HashMap<>();
        }

        @Override
        public void setNextReader(LeafReaderContext readerContext) {
            // No special handling needed for reader changes
        }

        @Override
        public boolean requiresBatchProcessing() {
            return true;
        }

        @Override
        public void processBatch(List<HitContext> hitContexts) throws IOException {
            logger.info("BatchHighlightProcessor.processBatch() called with {} documents", hitContexts.size());
            
            // Group all contexts by highlighter across all documents
            Map<Highlighter, List<BatchHighlightContext>> batchContextsByHighlighter = new HashMap<>();
            
            for (HitContext hitContext : hitContexts) {
                Map<String, HighlightField> highlightFields = new HashMap<>();
                Map<Highlighter, List<FieldHighlightContext>> groupedContexts = new HashMap<>();
                
                // Build contexts for this hit
                for (String field : contextBuilders.keySet()) {
                    FieldHighlightContext fieldContext = contextBuilders.get(field).apply(hitContext);
                    Highlighter highlighter = getHighlighter(fieldContext.field);
                    groupedContexts.computeIfAbsent(highlighter, k -> new ArrayList<>()).add(fieldContext);
                }
                
                // Store for batch processing
                for (Map.Entry<Highlighter, List<FieldHighlightContext>> entry : groupedContexts.entrySet()) {
                    Highlighter highlighter = entry.getKey();
                    List<FieldHighlightContext> contexts = entry.getValue();
                    
                    if (highlighter instanceof BatchHighlighter && ((BatchHighlighter) highlighter).supportsBatchHighlighting()) {
                        // Add to batch collection
                        for (FieldHighlightContext context : contexts) {
                            BatchHighlightContext batchContext = new BatchHighlightContext(hitContext, context);
                            batchContextsByHighlighter.computeIfAbsent(highlighter, k -> new ArrayList<>()).add(batchContext);
                        }
                    } else {
                        // Process non-batch highlighters immediately
                        for (FieldHighlightContext context : contexts) {
                            HighlightField highlightField = highlighter.highlight(context);
                            if (highlightField != null) {
                                highlightFields.put(context.fieldName, new HighlightField(context.fieldName, highlightField.fragments()));
                            }
                        }
                    }
                }
                
                // Set non-batch highlights immediately
                if (!highlightFields.isEmpty()) {
                    hitContext.hit().highlightFields(highlightFields);
                }
            }
            
            // Now process all batch highlighters
            logger.info("Processing {} batch highlighters", batchContextsByHighlighter.size());
            for (Map.Entry<Highlighter, List<BatchHighlightContext>> entry : batchContextsByHighlighter.entrySet()) {
                BatchHighlighter batchHighlighter = (BatchHighlighter) entry.getKey();
                List<BatchHighlightContext> batchContexts = entry.getValue();
                logger.info("Batch highlighting {} contexts with highlighter {}", 
                    batchContexts.size(), batchHighlighter.getClass().getSimpleName());
                
                // Extract field contexts for batch processing
                List<FieldHighlightContext> fieldContexts = batchContexts.stream()
                    .map(bc -> bc.fieldContext)
                    .collect(Collectors.toList());
                
                // Perform batch highlighting
                Map<FieldHighlightContext, HighlightField> batchResults = batchHighlighter.batchHighlight(fieldContexts);
                
                // Distribute results back to hit contexts
                for (BatchHighlightContext batchContext : batchContexts) {
                    HighlightField highlightField = batchResults.get(batchContext.fieldContext);
                    if (highlightField != null) {
                        Map<String, HighlightField> hitHighlights = batchContext.hitContext.hit().getHighlightFields();
                        if (hitHighlights == null) {
                            hitHighlights = new HashMap<>();
                            batchContext.hitContext.hit().highlightFields(hitHighlights);
                        }
                        hitHighlights.put(batchContext.fieldContext.fieldName, 
                            new HighlightField(batchContext.fieldContext.fieldName, highlightField.fragments()));
                    }
                }
            }
        }
    }
    
    private static class BatchHighlightContext {
        final HitContext hitContext;
        final FieldHighlightContext fieldContext;
        
        BatchHighlightContext(HitContext hitContext, FieldHighlightContext fieldContext) {
            this.hitContext = hitContext;
            this.fieldContext = fieldContext;
        }
    }
}
