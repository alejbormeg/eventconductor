package io.mateu.workflow.worker.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.mateu.workflow.template.Templates;
import io.mateu.workflow.worker.api.TaskContext;
import io.mateu.workflow.worker.api.TaskFailure;
import io.mateu.workflow.worker.api.TaskHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The built-in {@code llm-call} task: asks the model what an LLM_CALL step's engine rendered, and maps
 * the answer back into process variables.
 *
 * <ul>
 *   <li>The connection (provider, model, key, defaults) is this worker's configuration; the request
 *       names it and may override the model, temperature and max tokens.</li>
 *   <li>With a {@code responseSchema}, the answer must be one JSON document valid against it. It is
 *       parsed (a Markdown code fence around it is tolerated) and validated; an answer that does not
 *       parse or validate fails the step with {@code LLM_INVALID_OUTPUT} and the errors — retryable,
 *       since another sample may well be valid.</li>
 *   <li>The answer goes into {@code outputVariable} (the text, or the JSON document) and through
 *       {@code output} — JEXL over {@code {text, json, usage, model, finishReason}}; the token usage
 *       into {@code usageVariable} when asked for.</li>
 *   <li>Provider errors with an HTTP status: a client error (400, 401, 403, 404, 413, 422…) is final —
 *       retrying sends the same bad request or the same bad key; 408, 409, 429 and 5xx are retried per
 *       the step's {@code retries}, as are timeouts and I/O errors ({@code LLM_IO}).</li>
 * </ul>
 *
 * <p>Prompts and answers are never logged — they carry the process's data.
 */
public class LlmCallHandler implements TaskHandler<LlmCallHandler.Input, Map<String, Object>> {

    /**
     * The task variable the engine renders the request into. The task carries every process variable
     * too; the prompts are already rendered, so they are ignored.
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Input(JsonNode __llm) {
    }

    private static final Logger log = LoggerFactory.getLogger(LlmCallHandler.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern FENCE = Pattern.compile("```(?:json|JSON)?\\s*\\n?(.*?)\\n?```", Pattern.DOTALL);
    private static final Set<Integer> RETRYABLE_CLIENT_ERRORS = Set.of(408, 409, 429);
    private static final int MAX_REPORTED_ERRORS = 5;

    private final LlmWorkerProperties properties;
    private final ChatModelFactory factory;
    private final LlmCallMetrics metrics;
    private final Map<String, ChatModel> models = new ConcurrentHashMap<>();
    private final Map<String, Schema> schemas = new ConcurrentHashMap<>();
    private final SchemaRegistry schemaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private static final String META_SCHEMA = "https://json-schema.org/draft/2020-12/schema";
    private volatile Schema metaSchema;

    public LlmCallHandler(LlmWorkerProperties properties, ChatModelFactory factory, LlmCallMetrics metrics) {
        this.properties = properties;
        this.factory = factory;
        this.metrics = metrics == null ? LlmCallMetrics.NONE : metrics;
    }

    @Override
    public Map<String, Object> handle(Input input, TaskContext context) throws Exception {
        var request = input == null ? null : input.__llm();
        if (request == null || !request.isObject()) {
            throw new TaskFailure("LLM_REQUEST", "the task carries no rendered LLM request", false);
        }
        var connectionName = text(request, "connection");
        var connection = connectionName == null ? null : properties.getConnections().get(connectionName);
        if (connection == null) {
            throw new TaskFailure("LLM_CONFIG", "connection '" + connectionName + "' is not configured"
                    + " (workflow.llm.connections." + connectionName + ")", false);
        }
        var prompt = text(request, "prompt");
        if (prompt == null || prompt.isBlank()) {
            throw new TaskFailure("LLM_REQUEST", "the request has no prompt", false);
        }
        var model = models.computeIfAbsent(connectionName, name -> factory.create(name, connection));
        var modelName = firstSet(text(request, "model"), connection.getModel());
        var call = new LlmCallMetrics.Call(context == null ? null : context.workflowDefinitionId(),
                context == null ? null : context.stepId(), connectionName, modelName);

        var responseSchema = request.path("responseSchema");
        var structured = responseSchema.isObject() && !responseSchema.isEmpty();
        var schemaJson = structured ? responseSchema.toString() : null;
        var schema = structured ? schemaFor(schemaJson) : null;

        var messages = new ArrayList<Message>();
        var system = systemPrompt(text(request, "system"), schemaJson);
        if (system != null) {
            messages.add(new SystemMessage(system));
        }
        messages.add(new UserMessage(prompt));
        var options = options(model, request, modelName, structured && "native".equalsIgnoreCase(connection.getStructuredOutput())
                ? schemaJson : null);

        var started = System.nanoTime();
        ChatResponse response;
        try {
            response = model.call(new Prompt(messages, options));
        } catch (RuntimeException e) {
            metrics.call(call, LlmCallMetrics.ERROR, System.nanoTime() - started);
            var failure = failureOf(e, connectionName);
            log.info("LLM_CALL {} on {} failed in {} ms: {} (step {})", modelName, connectionName,
                    (System.nanoTime() - started) / 1_000_000, failure.code(), call.step());
            throw failure;
        }
        var elapsed = System.nanoTime() - started;
        var answer = answerOf(response, modelName);
        metrics.tokens(call, "input", answer.inputTokens());
        metrics.tokens(call, "output", answer.outputTokens());

        Object json = null;
        String text = answer.text();
        if (structured) {
            try {
                var document = extractJson(text);
                var errors = schema.validate(document, InputFormat.JSON);
                if (!errors.isEmpty()) {
                    var reported = errors.stream().limit(MAX_REPORTED_ERRORS)
                            .map(error -> error.getInstanceLocation() + ": " + error.getMessage()).toList();
                    throw invalid(answer, "the answer does not match the responseSchema — "
                            + String.join("; ", reported) + (errors.size() > MAX_REPORTED_ERRORS ? "; …" : ""));
                }
                json = JSON.readValue(document, Object.class);
                text = document;
            } catch (TaskFailure invalidOutput) {
                metrics.call(call, LlmCallMetrics.INVALID_OUTPUT, elapsed);
                log.info("LLM_CALL {} on {}: invalid output in {} ms, {} in / {} out tokens (step {})", answer.model(),
                        connectionName, elapsed / 1_000_000, answer.inputTokens(), answer.outputTokens(), call.step());
                throw invalidOutput;
            }
        }
        metrics.call(call, LlmCallMetrics.SUCCESS, elapsed);
        log.info("LLM_CALL {} on {}: {} ms, {} in / {} out tokens, finish {} (step {})", answer.model(), connectionName,
                elapsed / 1_000_000, answer.inputTokens(), answer.outputTokens(), answer.finishReason(), call.step());
        return outputs(request, text, json, answer);
    }

    /** The system prompt, with — for structured output — the schema and the instruction to answer with it alone. */
    static String systemPrompt(String system, String schemaJson) {
        if (schemaJson == null) {
            return system == null || system.isBlank() ? null : system;
        }
        var instruction = "Answer with exactly one JSON document that is valid against this JSON Schema, and"
                + " nothing else — no prose, no Markdown code fence:\n" + schemaJson;
        return system == null || system.isBlank() ? instruction : system + "\n\n" + instruction;
    }

    private static ChatOptions options(ChatModel model, JsonNode request, String modelName, String nativeSchema) {
        var defaults = model.getDefaultOptions();
        ChatOptions.Builder<?> builder = defaults == null ? ChatOptions.builder() : defaults.mutate();
        if (modelName != null) {
            builder.model(modelName);
        }
        if (request.path("temperature").isNumber()) {
            builder.temperature(request.path("temperature").asDouble());
        }
        if (request.path("maxTokens").isNumber()) {
            builder.maxTokens(request.path("maxTokens").asInt());
        }
        if (nativeSchema != null && builder instanceof StructuredOutputChatOptions.Builder<?> structured) {
            structured.outputSchema(nativeSchema);
        }
        return builder.build();
    }

    /**
     * The step's schema, compiled once. Checked against the JSON Schema meta-schema first: a schema that
     * is not one would otherwise only show as answers that never validate, each of them retried.
     */
    private Schema schemaFor(String schemaJson) {
        var cached = schemas.get(schemaJson);
        if (cached != null) {
            return cached;
        }
        List<String> problems;
        try {
            problems = metaSchema().validate(schemaJson, InputFormat.JSON).stream()
                    .limit(MAX_REPORTED_ERRORS).map(error -> error.getInstanceLocation() + ": " + error.getMessage()).toList();
        } catch (RuntimeException e) {
            problems = List.of(String.valueOf(e.getMessage()));
        }
        if (!problems.isEmpty()) {
            throw new TaskFailure("LLM_CONFIG", "the responseSchema is not a valid JSON Schema — "
                    + String.join("; ", problems), false);
        }
        try {
            var schema = schemaRegistry.getSchema(schemaJson, InputFormat.JSON);
            schema.initializeValidators();
            schemas.put(schemaJson, schema);
            return schema;
        } catch (RuntimeException e) {
            throw new TaskFailure("LLM_CONFIG", "the responseSchema is not a valid JSON Schema: " + e.getMessage(), false);
        }
    }

    private Schema metaSchema() {
        var meta = metaSchema;
        if (meta == null) {
            meta = schemaRegistry.getSchema(SchemaLocation.of(META_SCHEMA));
            metaSchema = meta;
        }
        return meta;
    }

    /** What the model said, and what it cost. */
    record Answer(String text, String model, String finishReason, Integer inputTokens, Integer outputTokens,
                  Integer totalTokens) {
    }

    private static Answer answerOf(ChatResponse response, String requestedModel) {
        var generation = response == null ? null : response.getResult();
        var text = generation == null || generation.getOutput() == null ? null : generation.getOutput().getText();
        var finishReason = generation == null || generation.getMetadata() == null ? null
                : generation.getMetadata().getFinishReason();
        var metadata = response == null ? null : response.getMetadata();
        var usage = metadata == null ? null : metadata.getUsage();
        var model = firstSet(metadata == null ? null : metadata.getModel(), requestedModel);
        Integer input = usage == null ? null : usage.getPromptTokens();
        Integer output = usage == null ? null : usage.getCompletionTokens();
        Integer total = usage == null ? null : usage.getTotalTokens();
        return new Answer(text == null ? "" : text, model, finishReason, input, output, total);
    }

    /** The JSON document in an answer: the whole answer, the inside of a code fence, or the outermost braces. */
    static String extractJson(String text) {
        var trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty()) {
            throw new TaskFailure("LLM_INVALID_OUTPUT", "the answer is empty", true);
        }
        var candidates = new ArrayList<String>();
        candidates.add(trimmed);
        var fence = FENCE.matcher(trimmed);
        if (fence.find()) {
            candidates.add(fence.group(1).strip());
        }
        int open = indexOfAny(trimmed, '{', '[');
        int close = Math.max(trimmed.lastIndexOf('}'), trimmed.lastIndexOf(']'));
        if (open >= 0 && close > open) {
            candidates.add(trimmed.substring(open, close + 1));
        }
        for (var candidate : candidates) {
            try {
                return JSON.writeValueAsString(JSON.readTree(candidate));
            } catch (IOException notJson) {
                // next candidate
            }
        }
        throw new TaskFailure("LLM_INVALID_OUTPUT", "the answer is not a JSON document", true);
    }

    private static int indexOfAny(String text, char a, char b) {
        int i = text.indexOf(a), j = text.indexOf(b);
        return i < 0 ? j : j < 0 ? i : Math.min(i, j);
    }

    private static TaskFailure invalid(Answer answer, String reason) {
        var truncated = answer.finishReason() != null
                && Set.of("max_tokens", "length", "MAX_TOKENS", "LENGTH").contains(answer.finishReason());
        return new TaskFailure("LLM_INVALID_OUTPUT", reason
                + (truncated ? " (the answer was cut at maxTokens — raise it)" : ""), true);
    }

    private Map<String, Object> outputs(JsonNode request, String text, Object json, Answer answer) {
        var result = new LinkedHashMap<String, Object>();
        var usage = new LinkedHashMap<String, Object>();
        usage.put("model", answer.model());
        usage.put("inputTokens", answer.inputTokens());
        usage.put("outputTokens", answer.outputTokens());
        usage.put("totalTokens", answer.totalTokens() != null ? answer.totalTokens()
                : answer.inputTokens() == null || answer.outputTokens() == null ? null
                : answer.inputTokens() + answer.outputTokens());
        var outputVariable = text(request, "outputVariable");
        if (outputVariable != null && !outputVariable.isBlank()) {
            result.put(outputVariable, text);
        }
        var usageVariable = text(request, "usageVariable");
        if (usageVariable != null && !usageVariable.isBlank()) {
            result.put(usageVariable, usage);
        }
        var output = request.path("output");
        if (output.isObject()) {
            var context = new LinkedHashMap<String, Object>();
            context.put("text", text);
            context.put("json", json);
            context.put("usage", usage);
            context.put("model", answer.model());
            context.put("finishReason", answer.finishReason());
            output.properties().forEach(entry -> {
                try {
                    result.put(entry.getKey(), Templates.render("${" + entry.getValue().asText() + "}", context));
                } catch (Templates.TemplateException e) {
                    throw new TaskFailure("LLM_OUTPUT", "output '" + entry.getKey() + "': " + e.getMessage(), false);
                }
            });
        }
        return result;
    }

    /**
     * A provider error as a step failure. The status is read off the exception (or a cause) through a
     * {@code statusCode()} method — what the Anthropic and OpenAI SDK exceptions expose — so no provider
     * class is needed here.
     */
    static TaskFailure failureOf(Throwable error, String connectionName) {
        if (error instanceof TaskFailure failure) {
            return failure;
        }
        var reason = "connection '" + connectionName + "': " + summary(error);
        for (var cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            var status = statusOf(cause);
            if (status != null) {
                boolean retryable = status >= 500 || RETRYABLE_CLIENT_ERRORS.contains(status) || status < 400;
                return new TaskFailure("LLM_" + status, reason, retryable);
            }
            var name = cause.getClass().getSimpleName();
            if (cause instanceof InterruptedIOException || cause instanceof IOException
                    || name.endsWith("IoException") || name.contains("Timeout")) {
                return new TaskFailure("LLM_IO", reason, true);
            }
            if (name.equals("NonTransientAiException")) {
                return new TaskFailure("LLM_ERROR", reason, false);
            }
        }
        return new TaskFailure("LLM_ERROR", reason, true);
    }

    private static Integer statusOf(Throwable error) {
        try {
            var method = error.getClass().getMethod("statusCode");
            var value = method.invoke(error);
            return value instanceof Integer status ? status : null;
        } catch (ReflectiveOperationException | RuntimeException noStatus) {
            return null;
        }
    }

    private static String summary(Throwable error) {
        var message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return message.length() > 1_000 ? message.substring(0, 1_000) + "…" : message;
    }

    private static String firstSet(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static String text(JsonNode node, String field) {
        var value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
