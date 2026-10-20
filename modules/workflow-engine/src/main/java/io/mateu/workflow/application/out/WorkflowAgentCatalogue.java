package io.mateu.workflow.application.out;

import io.mateu.workflow.domain.aggregates.AgentCall;
import java.util.List;
import java.util.Map;

/** Published Git catalogue projection; credentials and model details never cross this boundary. */
public interface WorkflowAgentCatalogue {
    record Agent(String id, String name, String description, List<String> mcp, List<String> rag,
                 List<String> peers, Map<String, Object> outputSchema, String revision) { }
    List<Agent> list();

    default void validate(AgentCall call) {
        var entry = list().stream().filter(agent -> agent.id().equals(call.ref())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown published workflow agent: " + call.ref()));
        checkSubset("mcp", call.mcp(), entry.mcp());
        checkSubset("rag", call.rag(), entry.rag());
        checkSubset("peers", call.peers(), entry.peers());
        if (entry.outputSchema() == null || entry.outputSchema().isEmpty())
            throw new IllegalArgumentException("Workflow agent has no outputSchema: " + call.ref());
    }

    private static void checkSubset(String name, List<String> selected, List<String> allowed) {
        if (selected != null && !selected.isEmpty() && (allowed == null || !allowed.containsAll(selected)))
            throw new IllegalArgumentException("Agent selection contains unauthorized " + name + " entries");
    }
}
