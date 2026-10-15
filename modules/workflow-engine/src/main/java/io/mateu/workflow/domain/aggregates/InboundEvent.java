package io.mateu.workflow.domain.aggregates;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A domain event as it reached the engine — from a destination's topic, the REST or MCP inject, the
 * embedded API or a {@code PUBLISH_EVENT} looped back — with its CloudEvents 1.0 context attributes.
 *
 * @param destination     the logical destination it arrived on ({@code workflow.events.destinations.<name>})
 * @param id              CloudEvents {@code id}; with {@code source}, what makes a delivery a duplicate
 * @param source          CloudEvents {@code source}
 * @param type            CloudEvents {@code type}; null for a plain record
 * @param subject         CloudEvents {@code subject}, optional
 * @param time            CloudEvents {@code time} as sent, optional
 * @param key             the Kafka record key (or the key given to an inject), optional
 * @param dataContentType CloudEvents {@code datacontenttype}, optional
 * @param data            the payload as text (JSON as a rule)
 * @param extensions      any other attribute (CloudEvents extensions), by name
 */
public record InboundEvent(String destination, String id, String source, String type, String subject, String time,
                           String key, String dataContentType, String data, Map<String, String> extensions) {

    public InboundEvent {
        extensions = extensions == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(extensions));
    }

    /** A copy arrived on another destination. */
    public InboundEvent onDestination(String newDestination) {
        return new InboundEvent(newDestination, id, source, type, subject, time, key, dataContentType, data, extensions);
    }
}
