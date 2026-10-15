package io.mateu.workflow.analysis;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmCallRulesTest {

    private static Map<String, Object> llm(Object... pairs) {
        var map = new HashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    void aConnectionAPromptAndSomewhereToKeepTheAnswerIsValid() {
        assertThat(LlmCallRules.problems(llm("connection", "claude", "prompt", "Hi ${name}",
                "outputVariable", "answer"), "s")).isEmpty();
        assertThat(LlmCallRules.problems(llm("connection", "claude", "prompt", "Classify ${text}",
                "model", "claude-opus-5-5", "system", "You classify.", "temperature", 0.2, "maxTokens", 512,
                "responseSchema", Map.of("type", "object"), "output", Map.of("category", "json.category"),
                "usageVariable", "usage"), "s")).isEmpty();
    }

    @Test
    void theRulesRefuseWhatCouldNotWork() {
        assertThat(LlmCallRules.problems(null, "s")).containsExactly("s must define an llm block.");
        assertThat(LlmCallRules.problems(llm("prompt", "x", "outputVariable", "a"), "s"))
                .anyMatch(p -> p.contains("connection"));
        assertThat(LlmCallRules.problems(llm("connection", "c", "outputVariable", "a"), "s"))
                .anyMatch(p -> p.contains("prompt"));
        assertThat(LlmCallRules.problems(llm("connection", "c", "prompt", "x"), "s"))
                .anyMatch(p -> p.contains("output or outputVariable"));
        assertThat(LlmCallRules.problems(llm("connection", "c", "prompt", "x", "outputVariable", "a",
                "temperature", 3), "s")).anyMatch(p -> p.contains("temperature"));
        assertThat(LlmCallRules.problems(llm("connection", "c", "prompt", "x", "outputVariable", "a",
                "maxTokens", 0), "s")).anyMatch(p -> p.contains("maxTokens"));
        assertThat(LlmCallRules.problems(llm("connection", "c", "prompt", "x", "outputVariable", "a",
                "maxTokens", "1.5"), "s")).anyMatch(p -> p.contains("maxTokens"));
        assertThat(LlmCallRules.problems(llm("connection", "c", "prompt", "x", "outputVariable", "a",
                "responseSchema", "object"), "s")).anyMatch(p -> p.contains("responseSchema"));
        assertThat(LlmCallRules.problems(llm("connection", "c", "prompt", "x", "outputVariable", "not valid"), "s"))
                .anyMatch(p -> p.contains("not a valid variable name"));
    }

    @Test
    void noSecretOrCredentialEverGoesIntoADefinition() {
        assertThat(LlmCallRules.problems(llm("connection", "c", "prompt", "key ${secret:K}", "outputVariable", "a"), "s"))
                .anyMatch(p -> p.contains("must never reach a prompt"));
        assertThat(LlmCallRules.problems(llm("connection", "c", "prompt", "x", "outputVariable", "a",
                "apiKey", "sk-123"), "s")).anyMatch(p -> p.contains("never to a definition"));
    }
}
