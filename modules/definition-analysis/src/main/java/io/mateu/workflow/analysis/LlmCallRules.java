package io.mateu.workflow.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The structural rules of an {@code LLM_CALL} step's {@code llm} block, on its generic (parsed
 * JSON/YAML) form — shared, as code, by the engine and the Maven plugin. The template expressions in
 * it ({@code system}, {@code prompt}) and the {@code output} expressions are checked separately, by
 * whoever holds a JEXL engine.
 */
public final class LlmCallRules {

    /** Keys that would put a credential in a definition. The key is configuration of the connection. */
    public static final Set<String> CREDENTIAL_FIELDS = Set.of("apiKey", "api-key", "api_key", "token", "secret", "password");
    private static final Pattern VARIABLE = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    /** Problems with an llm block; {@code llm} is the block as a map (null when absent). */
    public static List<String> problems(Map<?, ?> llm, String where) {
        var problems = new ArrayList<String>();
        if (llm == null) {
            problems.add(where + " must define an llm block.");
            return problems;
        }
        if (!set(llm.get("connection"))) {
            problems.add(where + " must name a connection (workflow.llm.connections.<name>).");
        }
        if (!set(llm.get("prompt"))) {
            problems.add(where + " must define a prompt.");
        }
        var temperature = llm.get("temperature");
        if (temperature != null) {
            var value = number(temperature);
            if (value == null || value < 0 || value > 2) {
                problems.add(where + " temperature must be a number between 0 and 2.");
            }
        }
        var maxTokens = llm.get("maxTokens");
        if (maxTokens != null) {
            var value = number(maxTokens);
            if (value == null || value < 1 || value != Math.floor(value)) {
                problems.add(where + " maxTokens must be a positive integer.");
            }
        }
        var schema = llm.get("responseSchema");
        if (schema != null && !(schema instanceof Map<?, ?>)) {
            problems.add(where + " responseSchema must be a JSON Schema object.");
        }
        var output = llm.get("output");
        if (output != null && !(output instanceof Map<?, ?>)) {
            problems.add(where + " output must map variable names to expressions.");
        }
        boolean hasOutput = output instanceof Map<?, ?> map && !map.isEmpty();
        if (!hasOutput && !set(llm.get("outputVariable"))) {
            problems.add(where + " must keep the answer: define output or outputVariable.");
        }
        if (output instanceof Map<?, ?> map) {
            map.keySet().forEach(name -> checkVariable(String.valueOf(name), where + " output", problems));
        }
        for (var field : List.of("outputVariable", "usageVariable")) {
            if (set(llm.get(field))) {
                checkVariable(String.valueOf(llm.get(field)), where + " " + field, problems);
            }
        }
        for (var field : List.of("system", "prompt", "model")) {
            var value = llm.get(field);
            if (value != null && String.valueOf(value).contains("${secret:")) {
                problems.add(where + ": ${secret:…} is not allowed in an llm block (found in " + field
                        + ") — a secret must never reach a prompt.");
            }
        }
        for (var key : llm.keySet()) {
            if (CREDENTIAL_FIELDS.contains(String.valueOf(key))) {
                problems.add(where + " " + key + ": credentials belong to the connection's configuration"
                        + " (workflow.llm.connections.<name>.api-key), never to a definition.");
            }
        }
        return problems;
    }

    private static void checkVariable(String name, String where, List<String> problems) {
        if (!VARIABLE.matcher(name).matches()) {
            problems.add(where + " '" + name + "' is not a valid variable name.");
        }
    }

    private static Double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean set(Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }

    private LlmCallRules() {
    }
}
