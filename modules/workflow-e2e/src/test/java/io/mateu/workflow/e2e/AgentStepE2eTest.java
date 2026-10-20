package io.mateu.workflow.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.application.services.ProcessOperationsService;
import io.mateu.workflow.application.services.ProcessOperationsService.ProcessStateConflictException;
import io.mateu.workflow.application.updates.ProcessUpdateRejectedException;
import io.mateu.workflow.application.updates.ProcessUpdateService;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionCommand;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import io.mateu.workflow.security.AuthorizationContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AGENT steps end to end in embedded + memory mode, with the programmable worker standing in for the agent
 * runtime: the request it gets, one invocation per forEach item, and how a review (REVIEW_REQUIRED) and an
 * invocation in flight interplay with the operator's operations, process updates and redelivered replies.
 */
class AgentStepE2eTest extends AbstractE2eTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AuthorizationContext ALICE = new AuthorizationContext("alice", List.of(), List.of());
    private static final String ASSESSMENT = "{\"decision\":\"accept\"}";

    @Autowired ProcessOperationsService operations;
    @Autowired ProcessUpdateService updates;

    private void claim(String businessKey) {
        createProcess("agent-claims", businessKey, new io.mateu.workflow.dtos.Variable("claimId", "C-1"),
                new io.mateu.workflow.dtos.Variable("amount", "120"));
    }

    /** What the agent runtime sends back for the step's current invocation. */
    private void reply(String businessKey, StepExecutionStatus status, long sequence, boolean reviewResolved,
                       Variable... output) {
        var assess = step(businessKey, "assess");
        updateStepExecutionUseCase.handle(new UpdateStepExecutionCommand(assess.getId(), List.of(output), "", status,
                false, assess.getDispatchCount(), assess.getInvocationId(), reviewResolved, sequence));
    }

    private String variable(String businessKey, String name) {
        return process(businessKey).getVariables().stream().filter(v -> name.equals(v.name()))
                .map(Variable::value).findFirst().orElse(null);
    }

    @Test
    void theRuntimeGetsOnlyTheRenderedInputAndItsResultBecomesTheOutputVariable() throws Exception {
        worker.on("assess", TestWorker.succeed(TestWorker.var("assessment", ASSESSMENT)));

        claim("agent-1");

        var request = worker.received().stream().filter(r -> r.stepId().equals("assess")).findFirst().orElseThrow();
        assertThat(request.taskId()).isEqualTo("run-agent@1");
        assertThat(request.invocationId()).isNotBlank();
        assertThat(request.variables()).singleElement().satisfies(v -> assertThat(v.name()).isEqualTo("__agent"));
        var agent = JSON.readTree(request.variables().getFirst().value());
        assertThat(agent.path("agentRef").asText()).isEqualTo("claims-assessor");
        assertThat(agent.path("taskPrompt").asText()).isEqualTo("Assess claim C-1");
        assertThat(agent.path("input").path("amount").asText()).isEqualTo("120");
        assertThat(agent.path("mcp").toString()).isEqualTo("[\"crm\"]");
        assertThat(agent.path("requestDigest").asText()).hasSize(64);
        assertThat(process("agent-1").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(variable("agent-1", "assessment")).isEqualTo(ASSESSMENT);
    }

    @Test
    void inAForEachEveryItemIsAnInvocationOfItsOwn() throws Exception {
        worker.on("assess", (request, callback, invocation) -> {
            try {
                var input = JSON.readTree(request.variables().getFirst().value()).path("input");
                callback.complete(List.of(TestWorker.var("assessment",
                        "{\"claim\":\"" + input.path("claimId").asText() + "\"}")));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        createProcess("agent-foreach", "agent-each-1", new io.mateu.workflow.dtos.Variable("claims",
                "[{\"id\":\"C-1\"},{\"id\":\"C-2\"},{\"id\":\"C-3\"}]"));

        var requests = worker.received().stream().filter(r -> r.stepId().equals("assess")).toList();
        assertThat(requests).hasSize(3);
        assertThat(requests).extracting(TaskExecutionRequested::invocationId).doesNotContainNull().doesNotHaveDuplicates();
        assertThat(requests).extracting(TaskExecutionRequested::taskExecutionId).doesNotHaveDuplicates();
        var prompts = requests.stream().map(r -> {
            try {
                return JSON.readTree(r.variables().getFirst().value()).path("taskPrompt").asText();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).toList();
        assertThat(prompts).containsExactlyInAnyOrder("Assess claim C-1", "Assess claim C-2", "Assess claim C-3");
        assertThat(process("agent-each-1").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(variable("agent-each-1", "assessments")).contains("C-1").contains("C-2").contains("C-3");
    }

    @Test
    void aReviewHoldsTheProcessAndOnlyItsReconciliationReleasesIt() {
        worker.on("assess", TestWorker.deferForever());
        claim("agent-review-1");
        var id = process("agent-review-1").getId();

        reply("agent-review-1", StepExecutionStatus.REVIEW_REQUIRED, 1, false);

        assertThat(step("agent-review-1", "assess").getStatus()).isEqualTo(StepExecutionStatus.REVIEW_REQUIRED);
        assertThat(process("agent-review-1").getStatus()).isEqualTo(ProcessStatus.PAUSED);
        var assess = step("agent-review-1", "assess").getId();
        // No operation that moves a step: the hold is the runtime's to release.
        assertThatThrownBy(() -> operations.completeStep(id, assess, List.of(new Variable("assessment", ASSESSMENT)),
                "by hand", ALICE)).isInstanceOf(ProcessStateConflictException.class).hasMessageContaining("under review");
        assertThatThrownBy(() -> operations.retryFromStep(id, "assess", "again", ALICE))
                .isInstanceOf(ProcessStateConflictException.class).hasMessageContaining("under review");
        assertThatThrownBy(() -> operations.migrate(id, null, Map.of(), Map.of(), "move", ALICE))
                .isInstanceOf(ProcessStateConflictException.class).hasMessageContaining("under review");
        assertThatThrownBy(() -> operations.resume(id, ALICE)).hasMessageContaining("Reconcile");
        // A process update waits too (the process is PAUSED); setting variables moves no step and is allowed.
        assertThatThrownBy(() -> updates.submitInline(new ProcessUpdateService.UpdateRequest(id, null, "addNote",
                "{\"note\":\"x\"}", null, null, ALICE))).isInstanceOf(ProcessUpdateRejectedException.class)
                .hasMessageContaining("PAUSED");
        operations.setVariables(id, List.of(new Variable("note", "called the customer")), List.of(), "context", ALICE);
        assertThat(step("agent-review-1", "assess").getStatus()).isEqualTo(StepExecutionStatus.REVIEW_REQUIRED);

        // A RUNNING that does not say the review was resolved is not a reconciliation.
        reply("agent-review-1", StepExecutionStatus.RUNNING, 2, false);
        assertThat(process("agent-review-1").getStatus()).isEqualTo(ProcessStatus.PAUSED);
        reply("agent-review-1", StepExecutionStatus.RUNNING, 3, true);
        assertThat(process("agent-review-1").getStatus()).isEqualTo(ProcessStatus.RUNNING);
        reply("agent-review-1", StepExecutionStatus.COMPLETED, 4, false, new Variable("assessment", ASSESSMENT));

        assertThat(process("agent-review-1").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(variable("agent-review-1", "assessment")).isEqualTo(ASSESSMENT);
        assertThat(variable("agent-review-1", "note")).isEqualTo("called the customer");
    }

    @Test
    void anInvocationInFlightIsNeitherCompletedNorMovedByHand() {
        worker.on("assess", TestWorker.deferForever());
        claim("agent-flight-1");
        var id = process("agent-flight-1").getId();
        var assess = step("agent-flight-1", "assess");
        assertThat(assess.getStatus()).isEqualTo(StepExecutionStatus.PENDING);

        assertThatThrownBy(() -> operations.completeStep(id, assess.getId(), List.of(), "by hand", ALICE))
                .isInstanceOf(ProcessStateConflictException.class).hasMessageContaining("in flight");
        assertThatThrownBy(() -> operations.retryFromStep(id, "assess", "again", ALICE))
                .isInstanceOf(ProcessStateConflictException.class).hasMessageContaining("in flight");
        assertThatThrownBy(() -> operations.migrate(id, null, Map.of(), Map.of(), "move", ALICE))
                .isInstanceOf(ProcessStateConflictException.class).hasMessageContaining("in flight");

        reply("agent-flight-1", StepExecutionStatus.COMPLETED, 1, false, new Variable("assessment", ASSESSMENT));
        assertThat(process("agent-flight-1").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }

    @Test
    void redeliveredAndStaleRepliesChangeNothing() {
        worker.on("assess", TestWorker.deferForever());
        claim("agent-redelivery-1");
        StepExecution assess = step("agent-redelivery-1", "assess");

        // A reply that names another invocation (an earlier run's) is not this one's.
        updateStepExecutionUseCase.handle(new UpdateStepExecutionCommand(assess.getId(),
                List.of(new Variable("assessment", "{\"decision\":\"stale\"}")), "", StepExecutionStatus.COMPLETED,
                false, assess.getDispatchCount(), "another-invocation", false, 9L));
        assertThat(step("agent-redelivery-1", "assess").getStatus()).isEqualTo(StepExecutionStatus.PENDING);

        reply("agent-redelivery-1", StepExecutionStatus.RUNNING, 5, false);
        // An older checkpoint redelivered after a newer one cannot put the step under review.
        reply("agent-redelivery-1", StepExecutionStatus.REVIEW_REQUIRED, 4, false);
        assertThat(step("agent-redelivery-1", "assess").getStatus()).isEqualTo(StepExecutionStatus.RUNNING);

        reply("agent-redelivery-1", StepExecutionStatus.COMPLETED, 6, false, new Variable("assessment", ASSESSMENT));
        // Kafka redelivers a record whose processing it could not confirm: the same completion again.
        reply("agent-redelivery-1", StepExecutionStatus.COMPLETED, 6, false, new Variable("assessment", "{\"decision\":\"again\"}"));
        assertThat(process("agent-redelivery-1").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(variable("agent-redelivery-1", "assessment")).isEqualTo(ASSESSMENT);
    }

    @Test
    void aCompletionWithoutItsDeclaredOutputIsRefusedAsAFailureThatWillNotPass() {
        worker.on("assess", TestWorker.deferForever());
        claim("agent-invalid-1");

        // IllegalArgumentException: what the Kafka consumers' error handler classifies as not retryable (#565),
        // so the record is parked on dead-letter rather than redelivered forever; the step keeps waiting for a
        // valid outcome (or its timeout, which holds it for review).
        assertThatThrownBy(() -> reply("agent-invalid-1", StepExecutionStatus.COMPLETED, 1, false,
                new Variable("somethingElse", "{}"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reply("agent-invalid-1", StepExecutionStatus.COMPLETED, 2, false,
                new Variable("assessment", "not json"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(step("agent-invalid-1", "assess").getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        assertThat(variable("agent-invalid-1", "assessment")).isNull();
    }
}
