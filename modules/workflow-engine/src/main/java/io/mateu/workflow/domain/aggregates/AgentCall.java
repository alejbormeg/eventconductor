package io.mateu.workflow.domain.aggregates;

import java.util.List;

/**
 * A workflow invocation of a published agent. Null capability lists inherit; empty lists disable.
 *
 * @param allowSensitiveVariables true to let {@code taskPrompt} and {@code input} read the definition's sensitive
 *                                variables — they leave the engine for the agent runtime (and, through it, a model and
 *                                its tools), so a definition that reads one without it is refused at load, as an
 *                                LLM_CALL's {@code llm.allowSensitiveVariables}. Left out of the JSON when absent.
 */
public record AgentCall(String ref, String taskPrompt, Object input, List<String> mcp,
                        List<String> rag, List<String> peers, String outputVariable,
                        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                        Boolean allowSensitiveVariables) {
    public AgentCall {
        mcp = mcp == null ? null : List.copyOf(mcp);
        rag = rag == null ? null : List.copyOf(rag);
        peers = peers == null ? null : List.copyOf(peers);
    }

    /** The shape before {@code allowSensitiveVariables}: the step does not opt in. */
    public AgentCall(String ref, String taskPrompt, Object input, List<String> mcp,
                     List<String> rag, List<String> peers, String outputVariable) {
        this(ref, taskPrompt, input, mcp, rag, peers, outputVariable, null);
    }
}
