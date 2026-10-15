package io.mateu.workflow.worker.llm;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;

/**
 * The per-step LLM metrics — names and tags are a contract (the Grafana dashboard reads them):
 *
 * <ul>
 *   <li>Timer {@code eventconductor.llm.call} ({@code eventconductor_llm_call_seconds_*}), tags
 *       {@code workflow}, {@code step}, {@code connection}, {@code model}, {@code outcome}
 *       ({@code success} | {@code error} | {@code invalid_output});</li>
 *   <li>Counter {@code eventconductor.llm.tokens} ({@code eventconductor_llm_tokens_total}), tags
 *       {@code workflow}, {@code step}, {@code connection}, {@code model}, {@code type}
 *       ({@code input} | {@code output}).</li>
 * </ul>
 *
 * <p>Spring AI's own {@code gen_ai.client.operation} and {@code gen_ai.client.token.usage}
 * observations are recorded besides these, by the model; these add what only the step knows — which
 * workflow and step, which connection, and how the answer fared.
 */
public class LlmCallMetrics {

    public static final String CALL = "eventconductor.llm.call";
    public static final String TOKENS = "eventconductor.llm.tokens";

    public static final String SUCCESS = "success";
    public static final String ERROR = "error";
    public static final String INVALID_OUTPUT = "invalid_output";

    /** No registry: nothing recorded. */
    public static final LlmCallMetrics NONE = new LlmCallMetrics(null);

    private final MeterRegistry registry;

    public LlmCallMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Which call a measurement belongs to. */
    public record Call(String workflow, String step, String connection, String model) {
    }

    public void call(Call call, String outcome, long nanos) {
        if (registry == null) {
            return;
        }
        Timer.builder(CALL)
                .description("LLM_CALL steps' model calls, by workflow, step, connection, model and outcome")
                .tag("workflow", tag(call.workflow()))
                .tag("step", tag(call.step()))
                .tag("connection", tag(call.connection()))
                .tag("model", tag(call.model()))
                .tag("outcome", outcome)
                .register(registry)
                .record(Duration.ofNanos(nanos));
    }

    public void tokens(Call call, String type, Integer count) {
        if (registry == null || count == null || count <= 0) {
            return;
        }
        Counter.builder(TOKENS)
                .description("Tokens used by LLM_CALL steps, by workflow, step, connection, model and type (input|output)")
                .tag("workflow", tag(call.workflow()))
                .tag("step", tag(call.step()))
                .tag("connection", tag(call.connection()))
                .tag("model", tag(call.model()))
                .tag("type", type)
                .register(registry)
                .increment(count);
    }

    private static String tag(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
