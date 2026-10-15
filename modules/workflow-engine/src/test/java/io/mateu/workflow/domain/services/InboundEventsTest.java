package io.mateu.workflow.domain.services;

import io.mateu.workflow.domain.aggregates.InboundEvent;
import io.mateu.workflow.dtos.events.integration.ExternalEventRequested;
import io.mateu.workflow.template.Templates;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InboundEventsTest {

    private static final InboundEvent EVENT = new InboundEvent("bookings", "e-1", "pms", "com.acme.booking.created",
            "subj", "2026-10-15T10:00:00Z", "K-1", "application/json",
            "{\"bookingId\":\"B-1\",\"nights\":2,\"web\":true,\"guest\":{\"name\":\"Ana\"},\"tags\":[\"a\"],\"none\":null}",
            Map.of("tenant", "acme"));

    private static final Map<String, Object> CONTEXT = InboundEvents.context(EVENT);

    @Test
    void theContextCarriesTheAttributesAndTheParsedData() {
        assertThat(CONTEXT).containsEntry("id", "e-1").containsEntry("source", "pms").containsEntry("key", "K-1")
                .containsEntry("subject", "subj").containsEntry("destination", "bookings")
                .containsEntry("extensions", Map.of("tenant", "acme"));
        assertThat(CONTEXT.get("data")).isInstanceOf(Map.class);
        assertThat(InboundEvents.context(new InboundEvent("d", "i", "s", null, null, null, null, null, "not json", null))
                .get("data")).isEqualTo("not json");
    }

    @Test
    void filtersHoldOnlyWhenTrue() {
        assertThat(InboundEvents.accepts(null, CONTEXT)).isTrue();
        assertThat(InboundEvents.accepts("data.nights > 1 && data.web", CONTEXT)).isTrue();
        assertThat(InboundEvents.accepts("data.guest.name == 'Ana' && extensions.tenant == 'acme'", CONTEXT)).isTrue();
        assertThat(InboundEvents.accepts("data.nights > 5", CONTEXT)).isFalse();
        // Not a boolean: not true.
        assertThat(InboundEvents.accepts("data.bookingId", CONTEXT)).isFalse();
        assertThatThrownBy(() -> InboundEvents.accepts("data.nights >", CONTEXT)).isInstanceOf(Templates.TemplateException.class);
    }

    @Test
    void variablesDefaultToDataMembersAsText() {
        assertThat(InboundEvents.variables(null, CONTEXT)).containsExactly(
                Map.entry("bookingId", "B-1"), Map.entry("nights", "2"), Map.entry("web", "true"),
                Map.entry("guest", "{\"name\":\"Ana\"}"), Map.entry("tags", "[\"a\"]"));
        assertThat(InboundEvents.variables(Map.of(), CONTEXT)).isEmpty();
    }

    @Test
    void declaredVariablesAreRendered() {
        var templates = new LinkedHashMap<String, Object>();
        templates.put("booking", "${data.bookingId}");
        templates.put("label", "Booking ${data.bookingId} (${data.nights} nights)");
        templates.put("guest", "${data.guest}");
        templates.put("constant", 3);
        templates.put("missing", "${data.nope}");
        assertThat(InboundEvents.variables(templates, CONTEXT)).containsExactly(
                Map.entry("booking", "B-1"), Map.entry("label", "Booking B-1 (2 nights)"),
                Map.entry("guest", "{\"name\":\"Ana\"}"), Map.entry("constant", "3"));
    }

    @Test
    void theCorrelationKeyIsTheTemplateTheKeyOrTheSubject() {
        assertThat(InboundEvents.correlationKey("${data.bookingId}", EVENT, CONTEXT)).isEqualTo("B-1");
        assertThat(InboundEvents.correlationKey(null, EVENT, CONTEXT)).isEqualTo("K-1");
        var noKey = new InboundEvent("d", "i", "s", "t", "subj", null, null, null, "{}", null);
        assertThat(InboundEvents.correlationKey(null, noKey, InboundEvents.context(noKey))).isEqualTo("subj");
        var nothing = new InboundEvent("d", "i", "s", "t", null, null, null, null, "{}", null);
        assertThat(InboundEvents.correlationKey(null, nothing, InboundEvents.context(nothing))).isNull();
    }

    @Test
    void theDefaultBusinessKeyDependsOnlyOnTheEventsSourceAndId() {
        var key = InboundEvents.defaultBusinessKey("wf", "t", EVENT);
        assertThat(key).startsWith("wf-evt-t-").hasSize("wf-evt-t-".length() + 16);
        var redelivered = new InboundEvent("other", "e-1", "pms", "x", null, null, null, null, "{}", null);
        assertThat(InboundEvents.defaultBusinessKey("wf", "t", redelivered)).isEqualTo(key);
        var another = new InboundEvent("bookings", "e-2", "pms", "x", null, null, null, null, "{}", null);
        assertThat(InboundEvents.defaultBusinessKey("wf", "t", another)).isNotEqualTo(key);
        assertThat(InboundEvents.receiptKey("r", EVENT)).hasSize(64).isNotEqualTo(InboundEvents.receiptKey("r2", EVENT));
    }

    @Test
    void aPublishedEventReadsBackWithTheAttributesTheKafkaSenderWrites() {
        var published = new ExternalEventRequested("se-1", "bookings", "com.acme.x", "K", null, "{\"a\":1}", "p-1",
                "wf", "announce", "bk", "2026-10-15T10:00:00Z");
        var event = InboundEvents.fromPublished(published);
        assertThat(event.id()).isEqualTo("se-1");
        assertThat(event.source()).isEqualTo("eventconductor/wf");
        assertThat(event.subject()).isEqualTo("bk");
        assertThat(event.key()).isEqualTo("K");
        assertThat(event.extensions()).containsEntry("processid", "p-1").containsEntry("stepid", "announce");
    }
}
