package io.mateu.workflow.domain.aggregates;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code WAIT_FOR_MESSAGE} only: the domain event that is this step's message. Carried in the
 * {@code .ec} file as the step's {@code onEvent} block.
 *
 * <p>An event of {@code type} arriving on {@code destination} for which {@code filter} holds is turned
 * into the message the step waits for — {@code MessageReceived(messageName, correlationKey, variables)}
 * — and dispatched like any other message, so everything a wait does (the indexed subscription,
 * routing, deadlines, on-timeout routes, compensation) applies unchanged. The step still names its
 * {@code messageName} and {@code correlationExpression}: the key the event renders must equal the key
 * the step's expression gives over the process.
 *
 * <p>Templates and the filter are evaluated over the event context (see {@link EventTrigger}).
 *
 * @param destination    a logical destination ({@code workflow.events.destinations.<name>})
 * @param type           the CloudEvents type to match; {@code *} is a wildcard
 * @param correlationKey optional text template for the correlation key the event carries; null means
 *                       the event's key (the Kafka record key), else its {@code subject}
 * @param filter         optional JEXL over the event that must be true
 * @param variables      the variables the message carries into the process, each a template; null
 *                       means the top-level members of {@code data}; empty means none
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"destination", "type", "correlationKey", "filter", "variables"})
public record EventWait(String destination, String type, String correlationKey, String filter,
                        Map<String, Object> variables) {

    public EventWait {
        destination = destination == null ? null : destination.trim();
        type = type == null ? null : type.trim();
        correlationKey = correlationKey == null || correlationKey.isBlank() ? null : correlationKey;
        filter = filter == null || filter.isBlank() ? null : filter;
        variables = variables == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
    }
}
