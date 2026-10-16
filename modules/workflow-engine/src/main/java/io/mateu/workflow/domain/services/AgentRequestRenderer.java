package io.mateu.workflow.domain.services;

import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.template.Templates;

import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit data boundary: only the selected input is sent to the agent worker. */
public final class AgentRequestRenderer {
    public static final String REQUEST_VARIABLE = "__agent";
    public static final String TASK_ID = "run-agent@1";
    public static final String TOPIC = "agent-calls";

    public static Map<String, Object> render(Step step, Process process, String stepExecutionId,
                                              String deadlineAt) {
        var agent = step.agent();
        if (agent == null || agent.ref() == null || agent.ref().isBlank()
                || agent.outputVariable() == null || agent.outputVariable().isBlank()) {
            throw new IllegalArgumentException("AGENT requires agent.ref and agent.outputVariable");
        }
        var context = TemplateContext.of(process, step, stepExecutionId);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("schemaVersion", 1);
        payload.put("agentRef", agent.ref());
        payload.put("taskPrompt", agent.taskPrompt() == null ? "" : Templates.renderText(agent.taskPrompt(), context));
        payload.put("input", agent.input() == null ? Map.of() : Templates.render(agent.input(), context));
        if (agent.mcp() != null) payload.put("mcp", agent.mcp());
        if (agent.rag() != null) payload.put("rag", agent.rag());
        if (agent.peers() != null) payload.put("peers", agent.peers());
        payload.put("outputVariable", agent.outputVariable());
        payload.put("timeoutMillis", step.timeout());
        if (deadlineAt != null) payload.put("deadlineAt", deadlineAt);
        if (process.getInitiatingActor() != null) payload.put("initiatingActor", process.getInitiatingActor());
        return payload;
    }

    private AgentRequestRenderer() { }
}
