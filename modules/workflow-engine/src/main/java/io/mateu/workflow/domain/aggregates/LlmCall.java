package io.mateu.workflow.domain.aggregates;

import java.util.Map;

/**
 * What an {@code LLM_CALL} step asks a model. Carried in the {@code .ec} file as the step's {@code llm}
 * block. {@code system} and {@code prompt} are text templates the engine renders from the process
 * state before the task is dispatched; the call itself is made by the built-in {@code llm-call} worker
 * task, on a connection whose provider, model and API key are configuration — never the definition's.
 *
 * @param connection     a named connection ({@code workflow.llm.connections.<name>}): provider, model, key, defaults
 * @param model          overrides the connection's model
 * @param system         the system prompt (text template)
 * @param prompt         the user message (text template)
 * @param temperature    sampling temperature, 0–2; omitted unless set
 * @param maxTokens      the most output tokens; default the connection's
 * @param responseSchema a JSON Schema: the answer must be one JSON document valid against it (structured output)
 * @param output         process variable → JEXL over {@code {text, json, usage, model, finishReason}}
 * @param outputVariable the variable that receives the text (with a responseSchema, the JSON document)
 * @param usageVariable  the variable that receives {@code {model, inputTokens, outputTokens, totalTokens}}
 */
public record LlmCall(
        String connection,
        String model,
        String system,
        String prompt,
        Double temperature,
        Integer maxTokens,
        Map<String, Object> responseSchema,
        Map<String, String> output,
        String outputVariable,
        String usageVariable
) {
}
