package io.mateu.workflow.test;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static io.mateu.workflow.test.WorkflowAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AGENT steps never leave the test: the kit's catalogue takes every reference, a stub answers run-agent@1. */
@EventConductorTest
class AgentStubTest {

    @Test
    void anAgentIsAnsweredByItsStubAndItsResultIsTheOutputVariable(WorkflowTestRuntime runtime) {
        var agent = runtime.whenAgent("assess").thenOutput(Map.of("decision", "accept"));

        var process = runtime.start("claim-assessment", "agent-1", Map.of("claimId", "C-1"));

        assertThat(process).isCompleted().hasVariable("assessment", Map.of("decision", "accept"));
        assertThat(agent.lastRequest()).containsEntry("agentRef", "claims-assessor")
                .containsEntry("taskPrompt", "Assess claim C-1").containsEntry("outputVariable", "assessment");
        assertThat(runtime.agentCatalogue().validatedRefs()).contains("claims-assessor");
        assertThat(runtime.agentCatalogue().list()).extracting(a -> a.id()).contains("claims-assessor");
    }

    @Test
    void aForEachIsOneInvocationPerItem(WorkflowTestRuntime runtime) {
        var agent = runtime.whenAgent("assess")
                .thenOutput("{\"n\":1}").thenOutput("{\"n\":2}").thenOutput("{\"n\":3}");

        var process = runtime.start("claim-batch", "agent-2", Map.of("claims", List.of("A", "B", "C")));

        assertThat(process).isCompleted().hasVariable("assessments");
        assertThat(agent.requests()).extracting(r -> r.get("taskPrompt"))
                .containsExactlyInAnyOrder("Assess claim A", "Assess claim B", "Assess claim C");
        assertThat(runtime.invocationsOf("assess")).hasSize(3);
    }

    @Test
    void aReviewHoldsTheProcessUntilTheRuntimeReconcilesIt(WorkflowTestRuntime runtime) {
        runtime.whenAgent("assess").thenReviewRequired().thenOutput("{\"decision\":\"refer\"}");

        var process = runtime.start("claim-assessment", "agent-3", Map.of("claimId", "C-3"));

        assertThat(process).hasStatus(ProcessStatus.PAUSED).hasStepStatus("assess", StepExecutionStatus.REVIEW_REQUIRED);
        runtime.resolveAgentReview(process, "assess");
        assertThat(process).isCompleted().hasVariable("assessment", Map.of("decision", "refer"));
        assertThatThrownBy(() -> runtime.resolveAgentReview(process, "assess")).isInstanceOf(AssertionError.class)
                .hasMessageContaining("no AGENT invocation under review");
    }

    @Test
    void aTimeoutHoldsTheInvocationAndTheRuntimesCancellationTimesTheStepOut(WorkflowTestRuntime runtime) {
        runtime.whenAgent("assess").thenNoReply().thenCancelled();

        var process = runtime.start("claim-assessment", "agent-4", Map.of("claimId", "C-4"));
        assertThat(process).isWaitingAt("assess");
        runtime.advanceTime(Duration.ofMinutes(6));

        // The AGENT default timeout is five minutes; its outcome is unknown, so it is held, not failed.
        assertThat(process).hasStatus(ProcessStatus.PAUSED).hasStepStatus("assess", StepExecutionStatus.REVIEW_REQUIRED);
        runtime.resolveAgentReview(process, "assess");
        assertThat(process).isFailed().hasStepStatus("assess", StepExecutionStatus.TIMEOUT);
    }

    @Test
    void aFailureAndAMissingStubFailTheStep(WorkflowTestRuntime runtime) {
        runtime.whenAgent("assess").thenFail("AGENT_REFUSED", "out of policy");
        assertThat(runtime.start("claim-assessment", "agent-5", Map.of("claimId", "C-5")))
                .isFailed().hasStepStatus("assess", StepExecutionStatus.ERROR);

        assertThat(runtime.start("claim-batch", "agent-6", Map.of("claims", List.of("A")))).isFailed();
    }

    @Test
    void anUnansweredInvocationCanBeCompletedByTheTestLikeAnyTask(WorkflowTestRuntime runtime) {
        runtime.whenAgent("assess").thenNoReply();
        var process = runtime.start("claim-assessment", "agent-7", Map.of("claimId", "C-7"));

        runtime.completeTask(process, "assess", Map.of("assessment", "{\"decision\":\"accept\"}"));

        assertThat(process).isCompleted();
    }
}
