package io.mateu.workflow.application.usecases.stepexecution.update;

import io.mateu.core.infra.JsonSerializer;
import io.mateu.workflow.application.out.*;
import io.mateu.workflow.application.services.MessageSubscriptionService;
import io.mateu.workflow.domain.aggregates.*;
import io.mateu.workflow.domain.aggregates.Process;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentStatusUpdateTest {
    private final StepExecutionRepository steps = mock(StepExecutionRepository.class);
    private final ProcessRepository processes = mock(ProcessRepository.class);
    private final ProcessLockService locks = mock(ProcessLockService.class);
    private final AtomicReference<StepExecution> execution = new AtomicReference<>();
    private final AtomicReference<Process> process = new AtomicReference<>();
    private final UpdateStepExecutionUseCase useCase = new UpdateStepExecutionUseCase(steps,
            mock(LogMessageRepository.class), processes, locks, mock(MessageSubscriptionService.class), mock(WorkflowMetrics.class),
            mock(io.mateu.workflow.application.services.AutoRetryService.class));

    @BeforeEach void setup() {
        var step = new Step("assess", "synthetic", StepType.AGENT, "Assess", null, null, null, null,
                false, null, null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null)
                .withAgent(new AgentCall("synthetic-assessor", null, null, null, null, null, "assessment"))
                .withOnTimeoutStepId("timeout");
        execution.set(StepExecution.builder().id("s").processId("p").stepId("assess").invocationId("i")
                .stepJson(JsonSerializer.toJson(step)).startedAt(LocalDateTime.now()).status(StepExecutionStatus.PENDING).build());
        process.set(Process.builder().id("p").variables(List.of(new Variable("original", "keep"))).status(ProcessStatus.RUNNING).build());
        when(steps.findById("s")).thenAnswer(call -> Optional.of(execution.get()));
        when(steps.findByProcessId("p")).thenAnswer(call -> List.of(execution.get()));
        when(processes.findById("p")).thenAnswer(call -> Optional.of(process.get()));
        when(locks.runExclusively(eq("p"), any())).thenAnswer(call -> { ((Runnable) call.getArgument(1)).run(); return true; });
        when(steps.save(any())).thenAnswer(call -> { execution.set(call.getArgument(0)); return "s"; });
        when(processes.save(any())).thenAnswer(call -> { process.set(call.getArgument(0)); return "p"; });
    }
    private void report(StepExecutionStatus status, long sequence, boolean resolved) {
        useCase.handle(new UpdateStepExecutionCommand("s", List.of(), "", status, false, "i", resolved, sequence));
    }

    @Test void reconciliationRenewsTimeoutAndOldReviewCannotRepauseTheProcess() {
        report(StepExecutionStatus.REVIEW_REQUIRED, 10, false);
        assertThat(process.get().getStatus()).isEqualTo(ProcessStatus.PAUSED);
        execution.set(execution.get().withStartedAt(LocalDateTime.now().minusHours(2)));
        report(StepExecutionStatus.RUNNING, 11, false);
        assertThat(execution.get().getStatus()).isEqualTo(StepExecutionStatus.REVIEW_REQUIRED);
        report(StepExecutionStatus.RUNNING, 11, true);
        assertThat(process.get().getStatus()).isEqualTo(ProcessStatus.RUNNING);
        assertThat(execution.get().getDeadlineAt()).isAfter(LocalDateTime.now().plusMinutes(4));
        report(StepExecutionStatus.REVIEW_REQUIRED, 10, false);
        assertThat(execution.get().getStatus()).isEqualTo(StepExecutionStatus.RUNNING);
        report(StepExecutionStatus.REVIEW_REQUIRED, 12, false);
        assertThat(process.get().getStatus()).isEqualTo(ProcessStatus.PAUSED);
    }

    @Test void safeCancellationOfATimedOutAgentBecomesNativeTimeout() {
        execution.get().holdAgent("TIMEOUT");
        process.set(process.get().withStatus(ProcessStatus.PAUSED));
        report(StepExecutionStatus.CANCELLED, 20, false);
        assertThat(execution.get().getStatus()).isEqualTo(StepExecutionStatus.TIMEOUT);
        assertThat(process.get().getStatus()).isEqualTo(ProcessStatus.RUNNING);
        assertThat(JsonSerializer.pojoFromJson(execution.get().getStepJson(), Step.class).onTimeoutStepId()).isEqualTo("timeout");
        assertThat(execution.get().popEvents()).anyMatch(event -> event instanceof io.mateu.workflow.dtos.events.domain.StepExecutionStatusChanged status
                && status.status() == io.mateu.workflow.dtos.events.integration.TaskStatus.TIMEOUT && status.nonRetryable());
    }

    @Test void anOldInvocationCannotWriteBusinessVariables() {
        useCase.handle(new UpdateStepExecutionCommand("s", List.of(new Variable("assessment", "{}")), "",
                StepExecutionStatus.COMPLETED, false, "old", false, 100L));
        assertThat(execution.get().getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        assertThat(process.get().getVariables()).containsExactly(new Variable("original", "keep"));
    }

    @Test void invalidAgentOutputDoesNotModifyBusinessVariables() {
        assertThatThrownBy(() -> useCase.handle(new UpdateStepExecutionCommand("s", List.of(new Variable("assessment", "invalid")), "",
                StepExecutionStatus.COMPLETED, false, "i", false, 1L))).isInstanceOf(IllegalArgumentException.class);
        assertThat(process.get().getVariables()).containsExactly(new Variable("original", "keep"));
    }

    @Test void anAgentReplyMustMatchBothItsInvocationAndCurrentDispatchAttempt() {
        execution.set(execution.get().toBuilder().dispatchCount(3).build());
        var result = List.of(new Variable("assessment", "{}"));
        useCase.handle(new UpdateStepExecutionCommand("s", result, "", StepExecutionStatus.COMPLETED,
                false, 2, "i", false, 1L));
        assertThat(execution.get().getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        assertThat(process.get().getVariables()).containsExactly(new Variable("original", "keep"));

        useCase.handle(new UpdateStepExecutionCommand("s", result, "", StepExecutionStatus.COMPLETED,
                false, 3, "i", false, 1L));
        assertThat(execution.get().getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(execution.get().getLastAgentStateSequence()).isEqualTo(1L);
        assertThat(process.get().getVariables()).contains(new Variable("assessment", "{}"));
    }

    @Test void aSiblingAgentReviewDefersTheAtomicRetryDecisionFromMain() {
        execution.set(execution.get().toBuilder().invocationId(null).dispatchCount(1)
                .stepJson("{\"id\":\"work\",\"type\":\"ACTION\",\"retries\":2}").build());
        var reviewing = StepExecution.builder().id("reviewing-agent").processId("p")
                .status(StepExecutionStatus.REVIEW_REQUIRED).invocationId("other-invocation").build();
        when(steps.findByProcessId("p")).thenAnswer(call -> List.of(execution.get(), reviewing));
        process.set(process.get().withStatus(ProcessStatus.PAUSED));

        useCase.handle(new UpdateStepExecutionCommand("s", List.of(), "Temporary failure",
                StepExecutionStatus.ERROR, false, 1));

        assertThat(execution.get().getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        assertThat(process.get().getStatus()).isEqualTo(ProcessStatus.PAUSED);
        verify(useCase.autoRetryService, never()).parkForRetryIfAttemptsLeft(any(), anyBoolean());
    }
}
