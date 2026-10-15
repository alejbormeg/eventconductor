package io.mateu.workflow.llmworker;

import io.mateu.workflow.worker.api.TaskRegistry;
import io.mateu.workflow.worker.llm.ChatModelFactory;
import io.mateu.workflow.worker.llm.LlmWorkerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.main.web-application-type=none",
        "workflow.llm.connections.claude.api-key=test-key"})
class LlmWorkerApplicationTest {

    @Autowired TaskRegistry registry;
    @Autowired LlmWorkerProperties properties;
    @Autowired ChatModelFactory factory;

    @Test
    void theLlmCallTaskIsServedAndBothProvidersCanBeBuilt() {
        assertThat(registry.refs()).contains("llm-call@1");
        var claude = properties.getConnections().get("claude");
        assertThat(claude.getProvider()).isEqualTo("anthropic");
        assertThat(factory.create("claude", claude).getDefaultOptions().getModel()).isEqualTo("claude-sonnet-5-5");
        var local = properties.getConnections().get("local");
        local.setApiKey("k");
        assertThat(factory.create("local", local).getDefaultOptions().getModel()).isEqualTo("local-model");
    }
}
