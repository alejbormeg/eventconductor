package io.mateu.workflow.e2e;

import io.mateu.workflow.application.out.CronScheduleRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * E2E-CRON-02 — scheduled starts with parameters, embedded + memory: the cron-vars.json definition's
 * own {@code schedules} entry and the classpath {@code workflows/cron.yaml} entry targeting it each
 * create processes (every second) carrying their own initial variables, under distinct business keys.
 * Same properties as {@link CronStartE2eTest}, so the two share one Spring context.
 */
@TestPropertySource(properties = {
        "workflow.cron-enabled=true",
        "workflow.cron-scan-interval-ms=200"
})
class CronSchedulesE2eTest extends AbstractE2eTest {

    @Autowired WorkflowDefinitionRepository workflowDefinitionRepository;
    @Autowired CronScheduleRepository cronScheduleRepository;

    @Test
    void cronYamlIsLoadedAsSchedulesNotAsADefinition() {
        // Every definition came from a definition file: cron.yaml was not read as one.
        assertThat(workflowDefinitionRepository.findAll()).allSatisfy(d -> assertThat(d.steps()).isNotEmpty());
        assertThat(cronScheduleRepository.findByWorkflowId("cron-vars")).singleElement()
                .satisfies(s -> assertThat(s.schedule().variables()).containsEntry("source", "cron-file")
                        .containsEntry("copies", "2"));
    }

    @Test
    void schedulesStartProcessesWithTheirOwnVariables() {
        worker.on("tick", TestWorker.succeed());

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(started("cron-vars-cron-s0-")).anySatisfy(p -> {
                assertThat(p.getStatus()).isEqualTo(ProcessStatus.COMPLETED);
                assertThat(p.getVariables()).contains(new Variable("source", "definition"), new Variable("region", "EMEA"));
            });
            assertThat(started("cron-vars-cron-f")).anySatisfy(p -> {
                assertThat(p.getStatus()).isEqualTo(ProcessStatus.COMPLETED);
                assertThat(p.getVariables()).contains(new Variable("source", "cron-file"), new Variable("copies", "2"));
            });
        });

        var keys = cronVarsProcesses().stream().map(Process::getBusinessKey).toList();
        assertThat(keys).doesNotHaveDuplicates();
        // The definition schedule and the cron.yaml entry are told apart by their keys.
        assertThat(started("cron-vars-cron-s0-")).allSatisfy(p ->
                assertThat(p.getVariables()).doesNotContain(new Variable("source", "cron-file")));
    }

    private List<Process> started(String businessKeyPrefix) {
        return cronVarsProcesses().stream().filter(p -> p.getBusinessKey().startsWith(businessKeyPrefix)).toList();
    }

    private List<Process> cronVarsProcesses() {
        return processRepository.findAll().stream()
                .filter(process -> "cron-vars".equals(process.getWorkflowDefinitionId()))
                .toList();
    }
}
