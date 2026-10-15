package io.mateu.workflow.worker.llm;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration of the built-in {@code llm-call} task: the named connections LLM_CALL steps refer to.
 * A definition names a connection and never holds a key; the key comes from here — which is to say from
 * an environment variable, a mounted Kubernetes secret, Vault, whatever the deployment already uses.
 *
 * <pre>
 *   workflow:
 *     llm:
 *       connections:
 *         claude:
 *           provider: anthropic
 *           model: claude-sonnet-5-5
 *           api-key: ${ANTHROPIC_API_KEY}
 *           max-tokens: 4096
 *           timeout: PT60S
 *         local:
 *           provider: openai                    # any OpenAI-compatible endpoint
 *           base-url: http://vllm.internal:8000
 *           model: llama-4-scout
 *           api-key: ${LOCAL_LLM_KEY:none}
 * </pre>
 */
@ConfigurationProperties(prefix = "workflow.llm")
@Getter
@Setter
public class LlmWorkerProperties {

    /** The Kafka topic the task is served on (a step's {@code topic} overrides it on the engine side). */
    private String topic = "llm-calls";

    private Map<String, Connection> connections = new LinkedHashMap<>();

    @Getter
    @Setter
    public static class Connection {
        /** {@code anthropic} or {@code openai} (OpenAI and every OpenAI-compatible endpoint). */
        private String provider = "anthropic";
        /** The model a step uses unless it names another. */
        private String model;
        /** Optional: a proxy, a gateway or a self-hosted OpenAI-compatible server. */
        private String baseUrl;
        /** The API key. Configuration only — feed it from the environment or a secret. */
        private String apiKey;
        /** Default temperature; unset leaves the provider's default (current Claude models accept no other). */
        private Double temperature;
        /** Default most output tokens. */
        private Integer maxTokens = 4096;
        /** How long one call may take before it fails as {@code LLM_IO} (retried per the step's retries). */
        private Duration timeout = Duration.ofSeconds(60);
        /**
         * How a step's {@code responseSchema} is enforced: {@code prompt} (default — the schema goes into
         * the system prompt, the answer is parsed and validated; works with any model) or {@code native}
         * (also handed to the provider's structured-output support, which constrains the model; the
         * schema must then be one the provider accepts).
         */
        private String structuredOutput = "prompt";
    }
}
