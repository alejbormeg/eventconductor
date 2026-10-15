package io.mateu.workflow.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionCommand;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import io.mateu.workflow.worker.api.Cancellations;
import io.mateu.workflow.worker.api.TaskDispatcher;
import io.mateu.workflow.worker.api.TaskRegistry;
import io.mateu.workflow.worker.api.TaskReplySink;
import io.mateu.workflow.worker.llm.LlmCallHandler;
import io.mateu.workflow.worker.llm.LlmCallMetrics;
import io.mateu.workflow.worker.llm.LlmWorkerAutoConfiguration;
import io.mateu.workflow.worker.llm.LlmWorkerProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LLM_CALL end to end: the engine renders the prompts, the real built-in llm-call task (worker-llm,
 * run through the worker runtime's TaskDispatcher as worker-embedded does) asks a stub model, and the
 * structured answer lands in the process — or, when the answer never matches the schema, the step spends
 * its retries, fails, and the saga rolls back.
 */
class LlmCallE2eTest extends AbstractE2eTest {

    final List<Prompt> prompts = new CopyOnWriteArrayList<>();
    volatile String answer = "{\"category\": \"room\", \"urgency\": 4}";
    SimpleMeterRegistry meters;

    @BeforeEach
    void wireTheLlmTask() {
        var properties = new LlmWorkerProperties();
        var claude = new LlmWorkerProperties.Connection();
        claude.setModel("claude-sonnet-5-5");
        claude.setApiKey("never-used");
        properties.getConnections().put("claude", claude);
        ChatModel model = prompt -> {
            prompts.add(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))),
                    ChatResponseMetadata.builder().model("claude-sonnet-5-5").usage(new DefaultUsage(200, 20, 220, null)).build());
        };
        meters = new SimpleMeterRegistry();
        var handler = new LlmCallHandler(properties, (name, connection) -> model, new LlmCallMetrics(meters));
        var sink = new TaskReplySink() {
            public void running(TaskExecutionRequested task) { }
            public void completed(TaskExecutionRequested task, List<Variable> variables) {
                updateStepExecutionUseCase.handle(new UpdateStepExecutionCommand(task.taskExecutionId(),
                        variables.stream().map(v -> new io.mateu.workflow.domain.aggregates.Variable(v.name(), v.value())).toList(),
                        "", StepExecutionStatus.COMPLETED));
            }
            public void failed(TaskExecutionRequested task, List<Variable> variables, String reason) {
                updateStepExecutionUseCase.handle(new UpdateStepExecutionCommand(task.taskExecutionId(),
                        List.of(), reason, StepExecutionStatus.ERROR));
            }
        };
        io.mateu.workflow.worker.api.TaskRegistration<?, ?> registration =
                new LlmWorkerAutoConfiguration().llmCallTaskRegistration(handler, properties);
        var dispatcher = new TaskDispatcher(new TaskRegistry(List.of(registration)),
                sink, Cancellations.NONE, new ObjectMapper(), true);
        worker.on("triage", (request, callback, invocation) -> dispatcher.dispatch(request));
        worker.on("log", TestWorker.succeed());
        worker.on("unlog", TestWorker.succeed());
    }

    @Test
    void theStructuredAnswerLandsInTheProcessAndTheReplyUsesIt() {
        createProcess("llm-call-saga", "lc-1", new Variable("bookingId", "B-1"),
                new Variable("complaint", "The shower is cold"));

        var process = process("lc-1");
        assertThat(process.getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(process.getVariables()).extracting("name", "value")
                .contains(org.assertj.core.groups.Tuple.tuple("category", "room"),
                        org.assertj.core.groups.Tuple.tuple("urgency", "4"));
        assertThat(process.getVariables()).anyMatch(v -> v.name().equals("triageUsage")
                && v.value().contains("\"inputTokens\":200") && v.value().contains("\"outputTokens\":20"));
        assertThat(process.getReply().payload()).isEqualTo("{\"bookingId\":\"B-1\",\"category\":\"room\",\"urgency\":4}");
        assertThat(prompts).hasSize(1);
        assertThat(prompts.getFirst().getUserMessage().getText()).isEqualTo("Booking B-1: The shower is cold");
        assertThat(meters.find("eventconductor.llm.call").tags("workflow", "llm-call-saga", "step", "triage",
                "outcome", "success").timer().count()).isEqualTo(1);
    }

    @Test
    void anAnswerThatNeverMatchesTheSchemaSpendsTheRetriesAndTheSagaRollsBack() {
        answer = "{\"category\": \"weather\", \"urgency\": 9}";

        createProcess("llm-call-saga", "lc-2", new Variable("bookingId", "B-2"),
                new Variable("complaint", "It rained"));

        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(process("lc-2").getStatus()).isEqualTo(ProcessStatus.COMPENSATED));
        assertThat(prompts).as("retries: 2 — an invalid answer is worth another sample").hasSize(3);
        assertThat(step("lc-2", "unlog").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(errorsOf("lc-2")).anyMatch(error -> error.contains("LLM_INVALID_OUTPUT") && error.contains("responseSchema"));
    }
}
