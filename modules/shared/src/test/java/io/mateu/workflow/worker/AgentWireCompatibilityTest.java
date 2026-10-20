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

    /** A message as an engine or worker of an older version wrote it: these fields did not exist yet. */
    private String withoutFields(Object event, String... fields) throws Exception {
        var tree = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(json.writeValueAsString(event));
        for (var field : fields) tree.remove(field);
        return json.writeValueAsString(tree);
    }

    @Test void attemptPriorityAndInvocationTravelTogetherAndEachIsOptionalOnTheWire() throws Exception {
        var full = new TaskExecutionRequested("se", "p", "w", "s", "run-agent@1", List.of(), 2, 80, "i");
        assertThat(json.readValue(json.writeValueAsString(full), DomainEvent.class)).isEqualTo(full);
        assertThat(full.effectivePriority()).isEqualTo(80);
        // The 2.33 shape (attempt and priority, no invocation) is still a constructor and still the wire.
        var v233 = new TaskExecutionRequested("se", "p", "w", "s", "charge", List.of(), 2, 80);
        assertThat(v233.invocationId()).isNull();
        assertThat(json.writeValueAsString(v233)).contains("\"priority\":80").doesNotContain("invocationId");
        // Without a priority, neither travels.
        assertThat(json.writeValueAsString(new TaskExecutionRequested("se", "p", "w", "s", "charge", List.of(), 2)))
                .doesNotContain("priority").doesNotContain("invocationId");
        // What a 2.33 engine sent, and a 2.32 one, read by this version.
        var from233 = (TaskExecutionRequested) json.readValue(withoutFields(full, "invocationId"), DomainEvent.class);
        assertThat(from233.priority()).isEqualTo(80);
        assertThat(from233.invocationId()).isNull();
        var from232 = (TaskExecutionRequested) json.readValue(withoutFields(full, "invocationId", "priority"), DomainEvent.class);
        assertThat(from232.attempt()).isEqualTo(2);
        assertThat(from232.effectivePriority()).isEqualTo(50);
    }

    @Test void repliesFromWorkersThatPredateAgentsReadWithNoInvocationAndNoReview() throws Exception {
        var reply = new TaskStatusChanged("se", TaskStatus.COMPLETED, List.of(), "p", false, 4, "i", true, 7L);
        assertThat(json.readValue(json.writeValueAsString(reply), DomainEvent.class)).isEqualTo(reply);
        var plain = new TaskStatusChanged("se", TaskStatus.COMPLETED, List.of(), "p", false, 4);
        assertThat(json.writeValueAsString(plain)).doesNotContain("invocationId").doesNotContain("reviewResolved")
                .doesNotContain("stateSequence");
        var old = (TaskStatusChanged) json.readValue(withoutFields(reply, "invocationId", "reviewResolved", "stateSequence"),
                DomainEvent.class);
        assertThat(old.attempt()).isEqualTo(4);
        assertThat(old.invocationId()).isNull();
        assertThat(old.reviewResolved()).isFalse();
        assertThat(old.stateSequence()).isNull();
        // The step-status event and the cancellation keep their old wire when no agent is involved.
        assertThat(json.writeValueAsString(new io.mateu.workflow.dtos.events.domain.StepExecutionStatusChanged(
                "se", TaskStatus.RUNNING, List.of(), "p", false))).doesNotContain("invocationId");
        var cancel = (TaskCancellationRequested) json.readValue(
                withoutFields(new TaskCancellationRequested("se", "i"), "invocationId"), DomainEvent.class);
        assertThat(cancel).isEqualTo(new TaskCancellationRequested("se"));
    }
}
