package io.mateu.workflow.worker.llm;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * OpenAI and OpenAI-compatible chat models (vLLM, Ollama, LiteLLM, Azure OpenAI's compatible
 * endpoint… through {@code base-url}). A class of its own so it is only loaded when spring-ai-openai is
 * present.
 */
final class OpenAiModels {

    static ChatModel create(LlmWorkerProperties.Connection connection, ObservationRegistry observations) {
        var options = OpenAiChatOptions.builder()
                .apiKey(connection.getApiKey())
                .timeout(connection.getTimeout())
                .maxRetries(0);
        if (connection.getBaseUrl() != null && !connection.getBaseUrl().isBlank()) {
            options.baseUrl(connection.getBaseUrl());
        }
        if (connection.getModel() != null && !connection.getModel().isBlank()) {
            options.model(connection.getModel());
        }
        if (connection.getMaxTokens() != null) {
            options.maxTokens(connection.getMaxTokens());
        }
        if (connection.getTemperature() != null) {
            options.temperature(connection.getTemperature());
        }
        return OpenAiChatModel.builder()
                .options(options.build())
                .observationRegistry(observations)
                .build();
    }

    private OpenAiModels() {
    }
}
