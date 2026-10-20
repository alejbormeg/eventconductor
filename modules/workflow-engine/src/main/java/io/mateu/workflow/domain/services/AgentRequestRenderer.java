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
        return render(step, process, stepExecutionId, deadlineAt, Map.of());
    }

    /**
     * The request, its templates read over the process and — for a forEach iteration — its scope ({@code item},
     * {@code index} and the loop's own names, typed), as HTTP_CALL and LLM_CALL render theirs.
     */
    public static Map<String, Object> render(Step step, Process process, String stepExecutionId,
                                              String deadlineAt, Map<String, Object> scope) {
        var agent = step.agent();
        if (agent == null || agent.ref() == null || agent.ref().isBlank()
                || agent.outputVariable() == null || agent.outputVariable().isBlank()) {
            throw new IllegalArgumentException("AGENT requires agent.ref and agent.outputVariable");
        }
        // What the agent gets leaves the engine for its runtime (and a model, and its tools): sensitive variables
        // stay out of the context unless the step opts in (agent.allowSensitiveVariables), as for an LLM_CALL. A
        // template naming one is refused at load; this also keeps them out of one that reaches them indirectly.
        if (!Boolean.TRUE.equals(agent.allowSensitiveVariables())) {
            var sensitive = io.mateu.workflow.application.services.SensitiveVariableMasking.namesOf(process);
            if (!sensitive.isEmpty() && process.getVariables() != null) {
                process = process.withVariables(process.getVariables().stream()
                        .filter(variable -> variable == null || !sensitive.test(variable.name()))
                        .toList());
            }
        }
        var context = TemplateContext.of(process, step, stepExecutionId, scope == null ? Map.of() : scope);
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
