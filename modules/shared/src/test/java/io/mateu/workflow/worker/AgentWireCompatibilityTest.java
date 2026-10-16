package io.mateu.workflow.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.events.integration.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class AgentWireCompatibilityTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test void legacyRequestsKeepTheirWireShapeAndNewRequestsRoundTrip() throws Exception {
        var old = new TaskExecutionRequested("se", "p", "w", "s", "action", List.of());
        assertThat(json.writeValueAsString(old)).doesNotContain("invocationId");
        var agent = new TaskExecutionRequested("se", "p", "w", "s", "run-agent@1", List.of(), 3, null, "i");
        assertThat(json.readValue(json.writeValueAsString(agent), DomainEvent.class)).isEqualTo(agent);
        assertThat(agent.attempt()).isEqualTo(3);
        var review = new TaskStatusChanged("se", TaskStatus.REVIEW_REQUIRED, List.of(), "p", false, 3, "i", false, null);
        assertThat(json.readValue(json.writeValueAsString(review), DomainEvent.class)).isEqualTo(review);
        assertThat(json.writeValueAsString(new TaskCancellationRequested("se"))).doesNotContain("invocationId");
        var cancel = new TaskCancellationRequested("se", "i");
        assertThat(json.readValue(json.writeValueAsString(cancel), DomainEvent.class)).isEqualTo(cancel);
    }

    @Test void workerRepliesEchoBothAttemptAndAgentInvocation() {
        var replies = new java.util.ArrayList<TaskStatusChanged>();
        var bridge = new org.springframework.cloud.stream.function.StreamOperations() {
            @Override public boolean send(String binding, Object payload) {
                assertThat(binding).isEqualTo("upstream");
                replies.add((TaskStatusChanged) payload);
                return true;
            }
            @Override public boolean send(String binding, Object payload, org.springframework.util.MimeType type) {
                return send(binding, payload);
            }
            @Override public boolean send(String binding, String channel, Object payload) {
                return send(binding, payload);
            }
            @Override public boolean send(String binding, String channel, Object payload, org.springframework.util.MimeType type) {
                return send(binding, payload);
            }
        };
        var task = new TaskExecutionRequested("se", "p", "w", "s", "run-agent@1", List.of(), 3, null, "i");
        WorkerReply.running(bridge, task);
        WorkerReply.completed(bridge, task, List.of());
        WorkerReply.failed(bridge, task, List.of());
        assertThat(replies).hasSize(3).allSatisfy(reply -> {
            assertThat(reply.attempt()).isEqualTo(3);
            assertThat(reply.invocationId()).isEqualTo("i");
        });
    }
}
