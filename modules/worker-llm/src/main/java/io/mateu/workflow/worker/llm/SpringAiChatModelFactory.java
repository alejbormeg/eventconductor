package io.mateu.workflow.worker.llm;

import io.micrometer.observation.ObservationRegistry;
import io.mateu.workflow.worker.api.TaskFailure;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.util.ClassUtils;

/**
 * The default {@link ChatModelFactory}: Spring AI's own chat models, built from the connection —
 * {@code anthropic} through {@code spring-ai-anthropic}, {@code openai} (and any OpenAI-compatible
 * endpoint, via {@code base-url}) through {@code spring-ai-openai}. Both are optional dependencies: a
 * connection whose provider is not on the classpath fails its steps with {@code LLM_CONFIG}.
 *
 * <p>The provider SDK's own retries are turned off: the step's {@code retries} are the one retry policy,
 * so every attempt is visible in the process and counted in the metrics. The observation registry is
 * handed on, so Spring AI records its {@code gen_ai.client.*} observations for every call.
 */
public class SpringAiChatModelFactory implements ChatModelFactory {

    static final String ANTHROPIC_MODEL = "org.springframework.ai.anthropic.AnthropicChatModel";
    static final String OPENAI_MODEL = "org.springframework.ai.openai.OpenAiChatModel";

    private final ObservationRegistry observations;

    public SpringAiChatModelFactory(ObservationRegistry observations) {
        this.observations = observations == null ? ObservationRegistry.NOOP : observations;
    }

    @Override
    public ChatModel create(String connectionName, LlmWorkerProperties.Connection connection) {
        var provider = connection.getProvider() == null ? "anthropic" : connection.getProvider().toLowerCase();
        if (connection.getApiKey() == null || connection.getApiKey().isBlank()) {
            throw new TaskFailure("LLM_CONFIG", "connection '" + connectionName + "' has no api-key"
                    + " (workflow.llm.connections." + connectionName + ".api-key)", false);
        }
        return switch (provider) {
            case "anthropic" -> {
                requireOnClasspath(ANTHROPIC_MODEL, "spring-ai-anthropic", connectionName);
                yield AnthropicModels.create(connection, observations);
            }
            case "openai" -> {
                requireOnClasspath(OPENAI_MODEL, "spring-ai-openai", connectionName);
                yield OpenAiModels.create(connection, observations);
            }
            default -> throw new TaskFailure("LLM_CONFIG", "connection '" + connectionName + "': unknown provider '"
                    + connection.getProvider() + "' (anthropic or openai)", false);
        };
    }

    private static void requireOnClasspath(String className, String artifact, String connectionName) {
        if (!ClassUtils.isPresent(className, SpringAiChatModelFactory.class.getClassLoader())) {
            throw new TaskFailure("LLM_CONFIG", "connection '" + connectionName + "' needs " + artifact
                    + " on the worker's classpath", false);
        }
    }
}
