package io.mateu.workflow.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import io.mateu.workflow.worker.FailureMarkers;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LLM_CALL, engine side: the prompts are rendered from the process state and dispatched to the
 * built-in llm-call task (here, the programmable test worker stands in for it), and a failure the
 * worker declares final — a provider 4xx — is not retried.
 */
class LlmCallDispatchE2eTest extends AbstractE2eTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private void create(String businessKey) {
        createProcess("llm-call-render", businessKey, new Variable("bookingId", "B-1"),
                new Variable("guest", "Ana"), new Variable("complaint", "The shower is cold"));
    }

    @Test
    void theRenderedPromptReachesTheLlmTask() throws Exception {
        worker.on("triage", TestWorker.succeed(TestWorker.var("category", "room")));

        create("llm-1");

        var request = worker.received().stream().filter(r -> r.stepId().equals("triage")).findFirst().orElseThrow();
        assertThat(request.taskId()).isEqualTo("llm-call@1");
        var llm = request.variables().stream().filter(v -> v.name().equals("__llm")).findFirst().orElseThrow().value();
        var rendered = JSON.readTree(llm);
        assertThat(rendered.path("connection").asText()).isEqualTo("claude");
        assertThat(rendered.path("model").asText()).isEqualTo("claude-sonnet-5-5");
        assertThat(rendered.path("system").asText()).isEqualTo("You triage complaints for booking B-1.");
        assertThat(rendered.path("prompt").asText()).isEqualTo("Ana wrote: The shower is cold");
        assertThat(rendered.path("maxTokens").asInt()).isEqualTo(256);
        assertThat(rendered.path("responseSchema").path("required").toString()).isEqualTo("[\"category\"]");
        assertThat(rendered.path("output").path("category").asText()).isEqualTo("json.category");
        assertThat(process("llm-1").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(process("llm-1").getVariables()).anyMatch(v -> v.name().equals("category") && v.value().equals("room"));
    }

    @Test
    void aFailureDeclaredFinalIsNotRetried() {
        worker.on("triage", TestWorker.failWith(FailureMarkers.NON_RETRYABLE + "LLM_401: invalid x-api-key"));

        create("llm-2");

        assertThat(worker.invocationsOf("triage")).as("retries: 2, but a final failure is not retried").isEqualTo(1);
        assertThat(step("llm-2", "triage").getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        assertThat(process("llm-2").getStatus()).isEqualTo(ProcessStatus.ERROR);
    }

    @Test
    void anOrdinaryFailureStillSpendsItsRetries() {
        worker.on("triage", TestWorker.failWith("LLM_529: overloaded"));

        create("llm-3");

        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(worker.invocationsOf("triage")).isEqualTo(3));
    }
}
