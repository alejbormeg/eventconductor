package io.mateu.workflow.domain.aggregates;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.template.Templates;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class AgentStepExecutionTest {
    private Step step() {
        return new Step("assess", "synthetic", StepType.AGENT, "Assess", null, null, null, null,
                false, null, null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null)
                .withAgent(new AgentCall("synthetic-assessor", "Assess ${caseId}", Map.of("caseId", "${caseId}"),
                        List.of(), null, null, "assessment"));
    }
    private Process process(String id) {
        return Process.builder().id("p").initiatingActor("synthetic-user")
                .variables(List.of(new Variable("caseId", id), new Variable("private", "never-dispatch"))).build();
    }

    @Test void dispatchesOnlySelectedInputAndPreservesInvocationAndPayloadOnRetry() throws Exception {
        var execution = StepExecution.create(step(), "p", 1).start(process("case-1"));
        assertThat(execution.getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        assertThat(execution.topic()).isEqualTo("agent-calls");
        assertThat(execution.getDeadlineAt()).isEqualTo(execution.getStartedAt().plusMinutes(5));
        var request = (TaskExecutionRequested) execution.popEvents().stream().filter(TaskExecutionRequested.class::isInstance).findFirst().orElseThrow();
        assertThat(request.taskId()).isEqualTo("run-agent@1");
        assertThat(request.attempt()).isEqualTo(execution.getDispatchCount());
        assertThat(request.invocationId()).isEqualTo(execution.getInvocationId());
        assertThat(request.variables()).hasSize(1);
        assertThat(request.variables().getFirst().name()).isEqualTo("__agent");
        var payload = new ObjectMapper().readTree(request.variables().getFirst().value());
        assertThat(payload.path("input").path("caseId").asText()).isEqualTo("case-1");
        assertThat(payload.path("initiatingActor").asText()).isEqualTo("synthetic-user");
        assertThat(payload.has("mcp")).isTrue();
        assertThat(payload.has("rag")).isFalse();
        assertThat(payload.toString()).doesNotContain("never-dispatch");
        execution.scheduleRetry(java.time.Duration.ZERO);
        execution.releaseForRetry();
        execution.start(process("case-2"));
        var retry = (TaskExecutionRequested) execution.popEvents().stream().filter(TaskExecutionRequested.class::isInstance).findFirst().orElseThrow();
        assertThat(retry.invocationId()).isEqualTo(request.invocationId());
        assertThat(retry.attempt()).isGreaterThan(request.attempt());
        assertThat(retry.variables()).isEqualTo(request.variables());
        execution.resetForRerun();
        execution.start(process("case-3"));
        assertThat(execution.getInvocationId()).isNotEqualTo(request.invocationId());
        assertThat(execution.getAgentRequest()).contains("case-3");
    }

    @Test void jsonNamespaceReadsStructuredResultsWithoutOpeningReflection() {
        assertThat(Templates.render("${json:parse(assessment).decision}", Map.of("assessment", "{\"decision\":\"accepted\"}")))
                .isEqualTo("accepted");
        assertThat(io.mateu.workflow.application.services.JEXLEvaluator.eval("json:parse(assessment).decision == 'accepted'",
                Map.of("assessment", "{\"decision\":\"accepted\"}"))).isEqualTo(true);
    }

    @Test void reviewCannotBeRestartedAndIsNonterminal() {
        var execution = StepExecution.create(step(), "p", 1).start(process("case-1"));
        execution.updateStatus(StepExecutionStatus.REVIEW_REQUIRED);
        assertThat(execution.getStatus().isTerminal()).isFalse();
        assertThatThrownBy(execution::resetForRerun).hasMessageContaining("Reconcile");
    }
}
