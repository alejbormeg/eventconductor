package io.mateu.workflow.worker.llm;

import org.springframework.ai.chat.model.ChatModel;

/**
 * Builds the Spring AI {@link ChatModel} for a named connection. Called once per connection; the
 * handler caches the result. Replaceable (it is a bean) — a test, or an application that builds its
 * models some other way, provides its own.
 */
@FunctionalInterface
public interface ChatModelFactory {

    ChatModel create(String connectionName, LlmWorkerProperties.Connection connection);
}
