package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.WorkflowTracing;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.infra.out.async.OutboxSignal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentExecutionPersistenceTest {
    @Test void durableMappingRetainsInvocationPayloadReviewAndCheckpoint() {
        var rows = mock(StepExecutionEntityRepository.class);
        var saved = new AtomicReference<StepExecutionEntity>();
        when(rows.save(any())).thenAnswer(call -> { saved.set(call.getArgument(0)); return saved.get(); });
        when(rows.findById("step-execution")).thenAnswer(call -> Optional.of(saved.get()));
        var repository = new StepExecutionDBRepository(rows, mock(OutboxMessageEntityRepository.class),
                WorkflowTracing.NOOP, mock(OutboxSignal.class), mock(ObjectProvider.class),
                io.mateu.workflow.application.services.VariableProtection.none());
        var execution = StepExecution.builder().id("step-execution").processId("synthetic-process")
                .stepJson("{\"id\":\"assess\",\"type\":\"AGENT\"}")
                .variables(List.of(new Variable("__agent", "{\"input\":{}}")))
                .invocationId("synthetic-invocation").agentRequest("{\"input\":{}}")
                .agentHoldReason("TIMEOUT").lastAgentStateSequence(20L).failureNonRetryable(true)
                .dispatchCount(7).iteration(2).superseded(true).loopDecision("LOOPED")
                .parentExecutionId("parent").itemIndex(3).scopeJson("{\"item\":\"case\"}").outputJson("{}")
                .status(StepExecutionStatus.REVIEW_REQUIRED).build();

        repository.save(execution);
        var restored = repository.findById("step-execution").orElseThrow();
        assertThat(restored.getInvocationId()).isEqualTo("synthetic-invocation");
        assertThat(restored.getAgentRequest()).isEqualTo(execution.getAgentRequest());
        assertThat(restored.getAgentHoldReason()).isEqualTo("TIMEOUT");
        assertThat(restored.getLastAgentStateSequence()).isEqualTo(20L);
        assertThat(restored.getStatus()).isEqualTo(StepExecutionStatus.REVIEW_REQUIRED);
        assertThat(restored.isFailureNonRetryable()).isTrue();
        assertThat(restored.getDispatchCount()).isEqualTo(7);
        assertThat(restored.getIteration()).isEqualTo(2);
        assertThat(restored.isSuperseded()).isTrue();
        assertThat(restored.getLoopDecision()).isEqualTo("LOOPED");
        assertThat(restored.getParentExecutionId()).isEqualTo("parent");
        assertThat(restored.getItemIndex()).isEqualTo(3);
        assertThat(restored.getScopeJson()).isEqualTo(execution.getScopeJson());
        assertThat(restored.getOutputJson()).isEqualTo("{}");
    }
}
