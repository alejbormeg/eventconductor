package io.mateu.workflow.analysis;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EventTriggerRulesTest {

    @Test
    void aTriggerNeedsADestinationAndAType() {
        assertThat(EventTriggerRules.triggerViolations(null, "bookings", "com.acme.booking.created", "triggers[0]")).isEmpty();
        assertThat(EventTriggerRules.triggerViolations(null, null, null, "triggers[0]"))
                .hasSize(2)
                .allMatch(v -> v.startsWith("triggers[0]: "));
        assertThat(EventTriggerRules.triggerViolations(null, "book ings", "t", "x")).singleElement()
                .asString().contains("not a valid destination");
    }

    @Test
    void anIdFollowsTheScheduleSyntax() {
        assertThat(EventTriggerRules.triggerViolations("on-created", "b", "t", "")).isEmpty();
        assertThat(EventTriggerRules.triggerViolations("-bad id", "b", "t", "")).singleElement()
                .asString().contains("not a valid trigger id");
    }

    @Test
    void repeatedIdsAreReportedOnce() {
        assertThat(EventTriggerRules.duplicateIdViolations(Arrays.asList("a", "b", "a", "a", null, null), "triggers"))
                .containsExactly("triggers: trigger id 'a' is declared more than once");
    }

    @Test
    void onEventOnlyOnAWait() {
        assertThat(EventTriggerRules.onEventPlacementViolations("s", "WAIT_FOR_MESSAGE", true)).isEmpty();
        assertThat(EventTriggerRules.onEventPlacementViolations("s", "ACTION", false)).isEmpty();
        assertThat(EventTriggerRules.onEventPlacementViolations("s", "ACTION", true)).hasSize(1);
    }

    @Test
    void idsAreDerivedFromTheTypeWhenAbsent() {
        assertThat(EventTriggerRules.effectiveIds(
                Arrays.asList(null, "mine", null, null, null),
                List.of("com.acme.booking.created", "x", "com.acme.booking.created", "com.acme.*", "*")))
                .containsExactly("com.acme.booking.created", "mine", "com.acme.booking.created-2", "com.acme.any", "any");
    }

    @Test
    void aDerivedIdDoesNotTakeADeclaredOne() {
        assertThat(EventTriggerRules.effectiveIds(Arrays.asList(null, "t"), List.of("t", "other")))
                .containsExactly("t-2", "t");
    }

    @Test
    void typesMatchExactlyOrByWildcard() {
        assertThat(EventTriggerRules.typeMatches("com.acme.booking.created", "com.acme.booking.created")).isTrue();
        assertThat(EventTriggerRules.typeMatches("com.acme.booking.created", "com.acme.booking.cancelled")).isFalse();
        assertThat(EventTriggerRules.typeMatches("com.acme.booking.*", "com.acme.booking.cancelled")).isTrue();
        assertThat(EventTriggerRules.typeMatches("com.acme.booking.*", "com.acme.payment.done")).isFalse();
        assertThat(EventTriggerRules.typeMatches("*.created", "com.acme.booking.created")).isTrue();
        assertThat(EventTriggerRules.typeMatches("com.(acme)", "com.(acme)")).isTrue();
        assertThat(EventTriggerRules.typeMatches("*", null)).isTrue();
        assertThat(EventTriggerRules.typeMatches("com.*", null)).isFalse();
        assertThat(EventTriggerRules.typeMatches(null, "x")).isFalse();
    }
}
