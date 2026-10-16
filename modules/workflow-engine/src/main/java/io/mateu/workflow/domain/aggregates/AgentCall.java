package io.mateu.workflow.domain.aggregates;

import java.util.List;

/** A workflow invocation of a published agent. Null capability lists inherit; empty lists disable. */
public record AgentCall(String ref, String taskPrompt, Object input, List<String> mcp,
                        List<String> rag, List<String> peers, String outputVariable) {
    public AgentCall {
        mcp = mcp == null ? null : List.copyOf(mcp);
        rag = rag == null ? null : List.copyOf(rag);
        peers = peers == null ? null : List.copyOf(peers);
    }
}
