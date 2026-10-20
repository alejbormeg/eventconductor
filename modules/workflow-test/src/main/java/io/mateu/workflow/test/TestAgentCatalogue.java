package io.mateu.workflow.test;

import io.mateu.workflow.application.out.WorkflowAgentCatalogue;
import io.mateu.workflow.domain.aggregates.AgentCall;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The agent catalogue of a test: every reference an AGENT step names is taken as published, with the
 * capabilities it asks for — a test never contacts the IA catalogue (it wins over a configured
 * {@code workflow.agents.catalogue-url}). What the engine validated is recorded ({@link #validatedRefs()}),
 * and {@link #list()} answers the editor's projection with those agents.
 */
public final class TestAgentCatalogue implements WorkflowAgentCatalogue {

    private final Set<String> validated = ConcurrentHashMap.newKeySet();

    @Override
    public List<Agent> list() {
        return validated.stream().sorted()
                .map(ref -> new Agent(ref, ref, "Test agent", null, null, null, java.util.Map.of("type", "object"), "test"))
                .toList();
    }

    @Override
    public void validate(AgentCall call) {
        if (call == null || call.ref() == null || call.ref().isBlank()) {
            throw new IllegalArgumentException("AGENT requires agent.ref");
        }
        validated.add(call.ref());
    }

    /** The agent references the engine validated (at import) in this context. */
    public Set<String> validatedRefs() {
        return Set.copyOf(validated);
    }
}
