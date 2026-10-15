package io.mateu.workflow.worker.llm;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;

/** Anthropic chat models. A class of its own so it is only loaded when spring-ai-anthropic is present. */
final class AnthropicModels {

    static ChatModel create(LlmWorkerProperties.Connection connection, ObservationRegistry observations) {
        var options = AnthropicChatOptions.builder()
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
        return AnthropicChatModel.builder()
                .options(options.build())
                .observationRegistry(observations)
                .build();
    }

    private AnthropicModels() {
    }
}
