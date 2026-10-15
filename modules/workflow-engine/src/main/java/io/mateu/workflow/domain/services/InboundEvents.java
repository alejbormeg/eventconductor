package io.mateu.workflow.domain.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.domain.aggregates.InboundEvent;
import io.mateu.workflow.template.Templates;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How a definition reads a domain event: the <em>event context</em> its filters and templates are
 * evaluated over, and the three things they produce — whether the event is wanted, a text (a business
 * key, a correlation key) and the variables it carries.
 *
 * <p>The context: {@code id}, {@code source}, {@code type}, {@code subject}, {@code time}, {@code key},
 * {@code destination}, {@code datacontenttype}, {@code data} — the payload parsed as JSON when it is
 * JSON (objects are maps, so {@code data.bookingId}), otherwise its text — and {@code extensions}.
 * Plain maps and values: a template gets these and nothing it could navigate from them. Evaluated in
 * the templates' sandbox ({@link Templates}).
 */
public final class InboundEvents {

    private static final ObjectMapper JSON = new ObjectMapper();

    private InboundEvents() {
    }

    /**
     * A {@code PUBLISH_EVENT}'s event as this engine receives it back (embedded loopback): the same
     * CloudEvents attributes the Kafka sender writes — id the step execution id, source
     * {@code eventconductor/<definition>}, subject the business key — so it is deduplicated exactly as
     * the record read back from the topic would be.
     */
    public static InboundEvent fromPublished(io.mateu.workflow.dtos.events.integration.ExternalEventRequested event) {
        var extensions = new LinkedHashMap<String, String>();
        if (event.processId() != null) extensions.put("processid", event.processId());
        if (event.stepId() != null) extensions.put("stepid", event.stepId());
        return new InboundEvent(event.destination(), event.eventId(), "eventconductor/" + event.workflowDefinitionId(),
                event.eventType(), event.businessKey(), event.time(), event.key(), "application/json", event.data(),
                extensions);
    }

    /** The context filters and templates are evaluated over. */
    public static Map<String, Object> context(InboundEvent event) {
        var context = new HashMap<String, Object>();
        context.put("id", event.id());
        context.put("source", event.source());
        context.put("type", event.type());
        context.put("subject", event.subject());
        context.put("time", event.time());
        context.put("key", event.key());
        context.put("destination", event.destination());
        context.put("datacontenttype", event.dataContentType());
        context.put("data", data(event.data()));
        context.put("extensions", new LinkedHashMap<>(event.extensions()));
        return context;
    }

    /** The payload as JSON structure when it is JSON, else as its text (null stays null). */
    static Object data(String data) {
        if (data == null) {
            return null;
        }
        var trimmed = data.strip();
        if (trimmed.isEmpty()) {
            return data;
        }
        char first = trimmed.charAt(0);
        if (first == '{' || first == '[' || first == '"' || Character.isDigit(first) || first == '-'
                || trimmed.equals("true") || trimmed.equals("false") || trimmed.equals("null")) {
            try {
                return JSON.readValue(trimmed, Object.class);
            } catch (Exception notJson) {
                return data;
            }
        }
        return data;
    }

    /**
     * Whether {@code filter} holds for the event: true when there is no filter, otherwise only when the
     * expression evaluates to {@code true}.
     *
     * @throws Templates.TemplateException when the expression cannot be evaluated
     */
    public static boolean accepts(String filter, Map<String, Object> context) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        return Boolean.TRUE.equals(Templates.render("${" + filter + "}", context));
    }

    /** A text template rendered over the event; null when there is none or it renders blank. */
    public static String text(String template, Map<String, Object> context) {
        if (template == null) {
            return null;
        }
        var rendered = Templates.renderText(template, context);
        return rendered == null || rendered.isBlank() ? null : rendered;
    }

    /**
     * The variables an event carries into a process: each template rendered, or — with none declared —
     * the top-level members of {@code data} when it is an object. Values are text, as process variables
     * are: a string as it is, anything else as JSON; null values are left out.
     */
    public static Map<String, String> variables(Map<String, Object> templates, Map<String, Object> context) {
        var variables = new LinkedHashMap<String, String>();
        if (templates == null) {
            if (context.get("data") instanceof Map<?, ?> data) {
                data.forEach((name, value) -> put(variables, String.valueOf(name), value));
            }
            return variables;
        }
        templates.forEach((name, template) -> put(variables, name, Templates.render(template, context)));
        return variables;
    }

    private static void put(Map<String, String> variables, String name, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String text) {
            variables.put(name, text);
            return;
        }
        if (value instanceof Number || value instanceof Boolean) {
            variables.put(name, String.valueOf(value));
            return;
        }
        try {
            variables.put(name, JSON.writeValueAsString(value));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            variables.put(name, String.valueOf(value));
        }
    }

    /**
     * The correlation key an event carries for an {@code onEvent} wait: the rendered template, or the
     * event's key, or its subject.
     */
    public static String correlationKey(String template, InboundEvent event, Map<String, Object> context) {
        if (template != null) {
            return text(template, context);
        }
        if (event.key() != null && !event.key().isBlank()) {
            return event.key();
        }
        return event.subject() == null || event.subject().isBlank() ? null : event.subject();
    }

    /**
     * The default business key of a process a trigger starts:
     * {@code <definition>-evt-<trigger>-<16 hex of SHA-256(source, id)>} — the same for every delivery
     * of one event, so a duplicate collapses in the creation idempotency guard.
     */
    public static String defaultBusinessKey(String definitionId, String triggerId, InboundEvent event) {
        return definitionId + "-evt-" + triggerId + "-" + sha256(event.source() + "\n" + event.id()).substring(0, 16);
    }

    /** The receipt key of a receiver and an event: a SHA-256 of the three, hex. */
    public static String receiptKey(String receiver, InboundEvent event) {
        return sha256(receiver + "\n" + event.source() + "\n" + event.id());
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
