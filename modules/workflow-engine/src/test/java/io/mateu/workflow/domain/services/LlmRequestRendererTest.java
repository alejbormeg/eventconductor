package io.mateu.workflow.domain.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.domain.aggregates.LlmCall;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepType;
import io.mateu.workflow.domain.aggregates.Variable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmRequestRendererTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Step STEP = new Step("triage", "wd", StepType.LLM_CALL, "Triage", null, "start", null, null,
            false, null, null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
    private static final Process PROCESS = Process.builder().id("p").businessKey("BK")
            .variables(List.of(new Variable("guest", "Ana"), new Variable("complaint", "Cold shower"))).build();

    private static LlmCall llm(String system, String prompt, Map<String, String> output, String outputVariable) {
        return new LlmCall("claude", null, system, prompt, null, null,
                Map.of("type", "object", "required", List.of("category")), output, outputVariable, "usage");
    }

    @Test
    void thePromptsAreRenderedFromTheProcessAndTheRestTravelsAsDeclared() throws Exception {
        var rendered = JSON.readTree(LlmRequestRenderer.render(STEP.withLlm(new LlmCall("claude", "claude-opus-5-5",
                "You triage complaints for ${process.businessKey}.", "${guest} wrote: ${complaint}", 0.2, 512,
                Map.of("type", "object"), Map.of("category", "json.category"), null, "usage")), PROCESS, "se-1"));
        assertThat(rendered.path("connection").asText()).isEqualTo("claude");
        assertThat(rendered.path("model").asText()).isEqualTo("claude-opus-5-5");
        assertThat(rendered.path("system").asText()).isEqualTo("You triage complaints for BK.");
        assertThat(rendered.path("prompt").asText()).isEqualTo("Ana wrote: Cold shower");
        assertThat(rendered.path("temperature").asDouble()).isEqualTo(0.2);
        assertThat(rendered.path("maxTokens").asInt()).isEqualTo(512);
        assertThat(rendered.path("responseSchema").path("type").asText()).isEqualTo("object");
        assertThat(rendered.path("output").path("category").asText()).isEqualTo("json.category");
        assertThat(rendered.path("usageVariable").asText()).isEqualTo("usage");
        assertThat(rendered.toString()).doesNotContainIgnoringCase("apiKey");
    }

    @Test
    void problemsCoverStructureTemplatesAndOutputExpressions() {
        assertThat(LlmRequestRenderer.problems(STEP.withLlm(llm(null, "Hi ${guest +}", null, "a"))))
                .anyMatch(p -> p.contains("llm.prompt"));
        assertThat(LlmRequestRenderer.problems(STEP.withLlm(llm("${(", "Hi", null, "a"))))
                .anyMatch(p -> p.contains("llm.system"));
        assertThat(LlmRequestRenderer.problems(STEP.withLlm(llm(null, "Hi", Map.of("v", "json.("), null))))
                .anyMatch(p -> p.contains("llm.output.v"));
        assertThat(LlmRequestRenderer.problems(STEP)).anyMatch(p -> p.contains("llm block"));
        assertThat(LlmRequestRenderer.problems(STEP.withLlm(llm("You classify.", "Classify: ${complaint}",
                Map.of("category", "json.category"), null)))).isEmpty();
        assertThatThrownBy(() -> LlmRequestRenderer.render(STEP, PROCESS, "se")).hasMessageContaining("no llm block");
    }
}
