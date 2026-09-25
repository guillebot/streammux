package io.github.guillebot.streammux.contracts.config;

import java.util.Map;

/**
 * Kafka Streams JSON enricher: extract a join key from the input payload, normalize
 * it with CEL, look the key up in a compacted topic materialized as a GlobalKTable,
 * and emit a wrapped envelope.
 *
 * @param joinKeyPath dotted or JSON Pointer path to the raw join field (e.g. {@code AccountNum})
 * @param joinKeyCel CEL expression over variable {@code key} (the extracted field as a string)
 * @param lookupTopic compacted table topic (treated as an input topic for allowlists)
 * @param enrichmentName key under the single enrichment object (e.g. {@code custdata})
 */
public record JsonEnricherConfig(
    String inputTopic,
    String outputTopic,
    String source,
    String joinKeyPath,
    String joinKeyCel,
    String lookupTopic,
    String enrichmentName,
    Map<String, String> streamProperties
) {}
