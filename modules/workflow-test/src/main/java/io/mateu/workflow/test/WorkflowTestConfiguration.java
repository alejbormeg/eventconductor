package io.mateu.workflow.test;

import io.mateu.workflow.application.out.EmbeddedTaskExecutor;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionUseCase;
import io.mateu.workflow.autoconfigure.WorkflowClockAutoConfiguration;
import io.mateu.workflow.worker.api.TaskDispatcher;
import io.mateu.workflow.worker.api.TaskRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * The kit's beans, imported by {@link EventConductorTest}: the movable clock (as the engine's clock
 * bean, so the engine installs it), the stubbing worker (as the engine's {@link EmbeddedTaskExecutor},
 * so the worker SDK's own executor backs off), the agent catalogue ({@link TestAgentCatalogue}) and the
 * {@link WorkflowTestRuntime}.
 *
 * <p>A {@code @TestConfiguration}, so a component scan never picks it up: it is in a context only
 * when a test imports it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class WorkflowTestConfiguration {

    @Bean(name = WorkflowClockAutoConfiguration.CLOCK_BEAN, defaultCandidate = false)
    public TestClock eventconductorClock() {
        return TestClock.startingNow();
    }

    @Bean
    StubbingTaskExecutor workflowTestTaskExecutor(ObjectProvider<UpdateStepExecutionUseCase> updateStepExecution,
                                                  WorkflowDefinitionRepository definitions,
                                                  ObjectProvider<TaskRegistry> taskRegistry,
                                                  ObjectProvider<TaskDispatcher> taskDispatcher) {
        return new StubbingTaskExecutor(updateStepExecution, definitions, taskRegistry, taskDispatcher);
    }

    /**
     * The agent catalogue AGENT steps are validated against: a test never contacts the IA catalogue, even when
     * the application configures {@code workflow.agents.catalogue-url}.
     */
    @Bean
    @org.springframework.context.annotation.Primary
    public TestAgentCatalogue workflowTestAgentCatalogue() {
        return new TestAgentCatalogue();
    }

    @Bean
    PublishedEventRecorder workflowTestPublishedEvents() {
        return new PublishedEventRecorder();
    }

    @Bean
    public WorkflowTestRuntime workflowTestRuntime(ApplicationContext context, StubbingTaskExecutor executor,
                                                   PublishedEventRecorder published) {
        var clock = (TestClock) context.getBean(WorkflowClockAutoConfiguration.CLOCK_BEAN);
        return new WorkflowTestRuntime(clock, executor, published, context);
    }
}
