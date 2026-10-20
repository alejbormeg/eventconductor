package io.mateu.workflow.domain.services;

import io.mateu.workflow.application.services.SensitiveFixtures;
import io.mateu.workflow.domain.aggregates.AgentCall;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.mateu.workflow.application.services.SensitiveFixtures.CARD;
import static org.assertj.core.api.Assertions.assertThat;

class AgentRequestRendererTest {

    private Step step(Boolean allowSensitiveVariables) {
        // ${vars...} is not how a definition would name it; an indirect read the load-time check cannot see.
        return new Step("assess", "payment", StepType.AGENT, "Assess", null, null, null, null,
                false, null, null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null)
                .withAgent(new AgentCall("claims-assessor", "Amount ${amount}", Map.of("card", "${cardNumber}"),
                        null, null, null, "assessment", allowSensitiveVariables));
    }

    @Test
    void withoutTheOptInSensitiveVariablesAreNotInTheContextAtAll() {
        var process = SensitiveFixtures.process("p-1");

        var request = AgentRequestRenderer.render(step(null), process, "se-1", null);
        assertThat(request.get("taskPrompt")).isEqualTo("Amount 120");
        assertThat(request.toString()).doesNotContain(CARD);

        var allowed = AgentRequestRenderer.render(step(true), process, "se-1", null);
        assertThat(allowed.toString()).contains(CARD);
    }

    @Test
    void anIterationReadsItsTypedScope() {
        var process = SensitiveFixtures.process("p-2");
        var step = step(null).withAgent(new AgentCall("claims-assessor", "Claim ${claim.id} (${index})", null,
                null, null, null, "assessment"));

        var request = AgentRequestRenderer.render(step, process, "se-2", "2026-03-01T00:05:00Z",
                Map.of("claim", Map.of("id", "C-9"), "index", 2));

        assertThat(request.get("taskPrompt")).isEqualTo("Claim C-9 (2)");
        assertThat(request.get("input")).isEqualTo(Map.of());
        assertThat(request.get("deadlineAt")).isEqualTo("2026-03-01T00:05:00Z");
    }
}
