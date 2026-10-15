package io.mateu.workflow.worker.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.worker.FailureMarkers;
import io.mateu.workflow.worker.api.Cancellations;
import io.mateu.workflow.worker.api.TaskContext;
import io.mateu.workflow.worker.api.TaskDispatcher;
import io.mateu.workflow.worker.api.TaskFailure;
import io.mateu.workflow.worker.api.TaskRegistration;
import io.mateu.workflow.worker.api.TaskRegistry;
import io.mateu.workflow.worker.api.TaskReplySink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The built-in llm-call task against a stub model: no provider is ever called. */
class LlmCallHandlerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SCHEMA = "{\"type\":\"object\",\"required\":[\"category\",\"urgency\"],"
            + "\"properties\":{\"category\":{\"type\":\"string\",\"enum\":[\"room\",\"billing\",\"staff\",\"other\"]},"
            + "\"urgency\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":5}}}";

    final List<Prompt> prompts = new CopyOnWriteArrayList<>();
    final List<String> builtFor = new CopyOnWriteArrayList<>();
    volatile Function<Prompt, ChatResponse> answer = prompt -> response("Hello", "end_turn");
    LlmWorkerProperties properties;
    SimpleMeterRegistry meters;
    LlmCallHandler handler;

    /** A provider error the way the Anthropic and OpenAI SDKs expose it: a statusCode() method. */
    static class ProviderException extends RuntimeException {
        private final int status;

        ProviderException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int statusCode() {
            return status;
        }
    }

    @BeforeEach
    void setUp() {
        properties = new LlmWorkerProperties();
        var claude = new LlmWorkerProperties.Connection();
        claude.setProvider("anthropic");
        claude.setModel("claude-sonnet-5-5");
        claude.setApiKey("test-key");
        properties.getConnections().put("claude", claude);
        meters = new SimpleMeterRegistry();
        ChatModel model = prompt -> {
            prompts.add(prompt);
            return answer.apply(prompt);
        };
        handler = new LlmCallHandler(properties, (name, connection) -> {
            builtFor.add(name);
            return model;
        }, new LlmCallMetrics(meters));
    }

    private static ChatResponse response(String text, String finishReason) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text),
                ChatGenerationMetadata.builder().finishReason(finishReason).build())),
                ChatResponseMetadata.builder().model("claude-sonnet-5-5").usage(new DefaultUsage(120, 30, 150, null)).build());
    }

    private static final TaskContext CONTEXT = new TaskContext() {
        public String taskExecutionId() { return "se-1"; }
        public String processId() { return "p-1"; }
        public String workflowDefinitionId() { return "complaints"; }
        public String stepId() { return "triage"; }
        public boolean isCancelled() { return false; }
        public void progress(String message) { }
    };

    private Map<String, Object> call(String request) throws Exception {
        return handler.handle(new LlmCallHandler.Input(JSON.readTree(request)), CONTEXT);
    }

    private double timer(String outcome) {
        var timer = meters.find("eventconductor.llm.call").tags("workflow", "complaints", "step", "triage",
                "connection", "claude", "outcome", outcome).timer();
        return timer == null ? 0 : timer.count();
    }

    @Test
    void aTextAnswerLandsInItsVariableWithTheUsageAndTheMetrics() throws Exception {
        var result = call("""
                {"connection": "claude", "system": "Be brief.", "prompt": "Say hello to Ana",
                 "outputVariable": "greeting", "usageVariable": "usage"}
                """);

        assertThat(result).containsEntry("greeting", "Hello");
        @SuppressWarnings("unchecked")
        var usage = (Map<String, Object>) result.get("usage");
        assertThat(usage).containsEntry("model", "claude-sonnet-5-5").containsEntry("inputTokens", 120)
                .containsEntry("outputTokens", 30).containsEntry("totalTokens", 150);
        var prompt = prompts.getFirst();
        assertThat(prompt.getSystemMessage().getText()).isEqualTo("Be brief.");
        assertThat(prompt.getUserMessage().getText()).isEqualTo("Say hello to Ana");
        assertThat(prompt.getOptions().getModel()).isEqualTo("claude-sonnet-5-5");
        assertThat(prompt.getOptions().getTemperature()).as("no temperature unless asked for").isNull();
        assertThat(timer("success")).isEqualTo(1);
        assertThat(meters.find("eventconductor.llm.tokens").tags("type", "input", "model", "claude-sonnet-5-5")
                .counter().count()).isEqualTo(120);
        assertThat(meters.find("eventconductor.llm.tokens").tags("type", "output", "step", "triage")
                .counter().count()).isEqualTo(30);
    }

    @Test
    void theStepMayOverrideModelTemperatureAndMaxTokensAndTheModelIsBuiltOncePerConnection() throws Exception {
        call("""
                {"connection": "claude", "model": "claude-opus-5-5", "prompt": "x", "temperature": 0.3,
                 "maxTokens": 77, "outputVariable": "a"}
                """);
        call("""
                {"connection": "claude", "prompt": "y", "outputVariable": "a"}
                """);

        var options = prompts.getFirst().getOptions();
        assertThat(options.getModel()).isEqualTo("claude-opus-5-5");
        assertThat(options.getTemperature()).isEqualTo(0.3);
        assertThat(options.getMaxTokens()).isEqualTo(77);
        assertThat(prompts.getFirst().getSystemMessages()).as("no system prompt given, none sent").isEmpty();
        assertThat(builtFor).containsExactly("claude");
        assertThat(meters.find("eventconductor.llm.call").tags("model", "claude-opus-5-5").timer().count()).isEqualTo(1);
    }

    @Test
    void aStructuredAnswerIsParsedValidatedAndMapped() throws Exception {
        answer = prompt -> response("Here it is:\n```json\n{\"category\": \"room\", \"urgency\": 4}\n```", "end_turn");

        var result = call("""
                {"connection": "claude", "system": "You triage.", "prompt": "Cold shower",
                 "responseSchema": %s,
                 "output": {"category": "json.category", "urgent": "json.urgency >= 4", "tokens": "usage.outputTokens"},
                 "outputVariable": "triage"}
                """.formatted(SCHEMA));

        assertThat(result).containsEntry("category", "room").containsEntry("urgent", true).containsEntry("tokens", 30);
        assertThat(JSON.readTree((String) result.get("triage")).path("urgency").asInt()).isEqualTo(4);
        assertThat(prompts.getFirst().getSystemMessage().getText()).startsWith("You triage.")
                .contains("valid against this JSON Schema").contains("\"urgency\"");
    }

    @Test
    void anAnswerThatBreaksTheSchemaFailsRetryablyWithTheReason() {
        answer = prompt -> response("{\"category\": \"weather\"}", "end_turn");

        assertThatThrownBy(() -> call("""
                {"connection": "claude", "prompt": "x", "responseSchema": %s, "output": {"c": "json.category"}}
                """.formatted(SCHEMA)))
                .isInstanceOfSatisfying(TaskFailure.class, failure -> {
                    assertThat(failure.code()).isEqualTo("LLM_INVALID_OUTPUT");
                    assertThat(failure.retryable()).isTrue();
                    assertThat(failure.getMessage()).contains("does not match the responseSchema").contains("urgency");
                });
        assertThat(timer("invalid_output")).isEqualTo(1);
        assertThat(timer("success")).isZero();
    }

    @Test
    void anAnswerThatIsNotJsonOrWasCutShortSaysSo() {
        answer = prompt -> response("I think it is about the room.", "end_turn");
        assertThatThrownBy(() -> call("""
                {"connection": "claude", "prompt": "x", "responseSchema": %s, "outputVariable": "a"}
                """.formatted(SCHEMA))).hasMessageContaining("not a JSON document");

        answer = prompt -> response("{\"category\": \"room\"", "max_tokens");
        assertThatThrownBy(() -> call("""
                {"connection": "claude", "prompt": "x", "responseSchema": %s, "outputVariable": "a"}
                """.formatted(SCHEMA))).hasMessageContaining("not a JSON document");

        answer = prompt -> response("{\"category\": \"room\"}", "max_tokens");
        assertThatThrownBy(() -> call("""
                {"connection": "claude", "prompt": "x", "responseSchema": %s, "outputVariable": "a"}
                """.formatted(SCHEMA))).hasMessageContaining("cut at maxTokens");
    }

    @Test
    void aClientErrorIsFinalWhileRateLimitsServerErrorsAndTimeoutsAreRetried() {
        var request = """
                {"connection": "claude", "prompt": "x", "outputVariable": "a"}
                """;
        answer = prompt -> { throw new ProviderException(401, "invalid x-api-key"); };
        assertThatThrownBy(() -> call(request)).isInstanceOfSatisfying(TaskFailure.class, failure -> {
            assertThat(failure.code()).isEqualTo("LLM_401");
            assertThat(failure.retryable()).isFalse();
            assertThat(failure.reason()).startsWith(FailureMarkers.NON_RETRYABLE).contains("invalid x-api-key");
        });

        answer = prompt -> { throw new IllegalStateException("wrapped", new ProviderException(400, "bad request")); };
        assertThatThrownBy(() -> call(request)).isInstanceOfSatisfying(TaskFailure.class,
                failure -> assertThat(failure.retryable()).isFalse());

        for (int status : List.of(429, 500, 529)) {
            answer = prompt -> { throw new ProviderException(status, "try later"); };
            assertThatThrownBy(() -> call(request)).isInstanceOfSatisfying(TaskFailure.class, failure -> {
                assertThat(failure.code()).isEqualTo("LLM_" + status);
                assertThat(failure.retryable()).isTrue();
            });
        }

        answer = prompt -> { throw new UncheckedIOException(new IOException("connection reset")); };
        assertThatThrownBy(() -> call(request)).isInstanceOfSatisfying(TaskFailure.class, failure -> {
            assertThat(failure.code()).isEqualTo("LLM_IO");
            assertThat(failure.retryable()).isTrue();
        });
        assertThat(timer("error")).isEqualTo(6);
    }

    @Test
    void misconfigurationIsFinal() {
        assertThatThrownBy(() -> call("""
                {"connection": "nope", "prompt": "x", "outputVariable": "a"}
                """)).isInstanceOfSatisfying(TaskFailure.class, failure -> {
            assertThat(failure.code()).isEqualTo("LLM_CONFIG");
            assertThat(failure.retryable()).isFalse();
            assertThat(failure.getMessage()).contains("workflow.llm.connections.nope");
        });
        assertThatThrownBy(() -> call("""
                {"connection": "claude", "prompt": "x", "responseSchema": {"type": 12}, "outputVariable": "a"}
                """)).isInstanceOfSatisfying(TaskFailure.class, failure -> assertThat(failure.retryable()).isFalse());
        assertThatThrownBy(() -> handler.handle(new LlmCallHandler.Input(null), CONTEXT))
                .hasMessageContaining("no rendered LLM request");
    }

    @Test
    void theDefaultFactoryRefusesAConnectionItCannotBuild() {
        var factory = new SpringAiChatModelFactory(null);
        var noKey = new LlmWorkerProperties.Connection();
        assertThatThrownBy(() -> factory.create("c", noKey)).hasMessageContaining("has no api-key");
        var unknown = new LlmWorkerProperties.Connection();
        unknown.setApiKey("k");
        unknown.setProvider("acme");
        assertThatThrownBy(() -> factory.create("c", unknown)).hasMessageContaining("unknown provider 'acme'");
    }

    @Test
    void theDefaultFactoryBuildsAnthropicAndOpenAiModelsWithoutCallingThem() {
        var factory = new SpringAiChatModelFactory(null);
        var anthropic = new LlmWorkerProperties.Connection();
        anthropic.setApiKey("k");
        anthropic.setModel("claude-sonnet-5-5");
        assertThat(factory.create("claude", anthropic).getDefaultOptions().getModel()).isEqualTo("claude-sonnet-5-5");
        var openai = new LlmWorkerProperties.Connection();
        openai.setProvider("openai");
        openai.setApiKey("k");
        openai.setBaseUrl("http://localhost:1");
        openai.setModel("gpt-oss");
        openai.setTemperature(0.1);
        assertThat(factory.create("local", openai).getDefaultOptions().getModel()).isEqualTo("gpt-oss");
    }

    @Test
    void throughTheTaskDispatcherTheRenderedVariableBindsAndOutputsBecomeVariables() {
        answer = prompt -> response("{\"category\": \"billing\", \"urgency\": 2}", "end_turn");
        TaskRegistration<?, ?> registration = new LlmWorkerAutoConfiguration().llmCallTaskRegistration(handler, properties);
        assertThat(registration.ref()).isEqualTo("llm-call@1");
        assertThat(registration.topic()).isEqualTo("llm-calls");
        var completed = new ArrayList<List<Variable>>();
        var failed = new ArrayList<String>();
        var sink = new TaskReplySink() {
            public void running(TaskExecutionRequested task) { }
            public void completed(TaskExecutionRequested task, List<Variable> variables) { completed.add(variables); }
            public void failed(TaskExecutionRequested task, List<Variable> variables, String reason) { failed.add(reason); }
        };
        var dispatcher = new TaskDispatcher(new TaskRegistry(List.of(registration)), sink, Cancellations.NONE, JSON, true);
        dispatcher.dispatch(new TaskExecutionRequested("se-9", "p", "complaints", "triage", "llm-call@1", List.of(
                new Variable("complaint", "Charged twice"),
                new Variable("__llm", "{\"connection\":\"claude\",\"prompt\":\"Charged twice\",\"responseSchema\":"
                        + SCHEMA + ",\"output\":{\"category\":\"json.category\",\"urgency\":\"json.urgency\"}}"))));

        assertThat(failed).isEmpty();
        assertThat(completed.getFirst()).extracting("name", "value")
                .contains(org.assertj.core.groups.Tuple.tuple("category", "billing"),
                        org.assertj.core.groups.Tuple.tuple("urgency", "2"));
    }
}
