package io.mateu.workflow.e2e;

import io.mateu.workflow.application.out.CronScheduleRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.CronFileSchedule;
import io.mateu.workflow.domain.aggregates.CronSchedule;
import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import io.mateu.workflow.infra.out.classpath.ClasspathCronScheduleLoader;
import io.mateu.workflow.infra.out.persistence.JpaCronScheduleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E-CRON-03 — on the durable path the schedules survive the database: a definition's own
 * {@code schedules} round-trip through its row, and the classpath {@code cron.yaml} entries are
 * stored in {@code cron_schedule}, where a re-import replaces them.
 */
class CronSchedulesJpaE2eTest extends AbstractJpaE2eTest {

    @Autowired WorkflowDefinitionRepository workflowDefinitionRepository;
    @Autowired CronScheduleRepository cronScheduleRepository;

    @Test
    void schedulesArePersistedAndCronFileEntriesReplacedPerSource() {
        assertThat(cronScheduleRepository).isInstanceOf(JpaCronScheduleRepository.class);
        assertThat(workflowDefinitionRepository.findById("cron-vars").orElseThrow().schedules()).containsExactly(
                new CronSchedule("* * * * * *", "UTC", Map.of("source", "definition", "region", "EMEA")));
        assertThat(cronScheduleRepository.findByWorkflowId("cron-vars")).containsExactly(new CronFileSchedule(
                ClasspathCronScheduleLoader.SOURCE, 0, "cron-vars",
                new CronSchedule("* * * * * *", null, Map.of("source", "cron-file", "copies", "2"))));

        cronScheduleRepository.replace("/srv/defs", List.of(
                new CronFileSchedule("/srv/defs", 0, "cron-vars", new CronSchedule("@daily"))));
        cronScheduleRepository.replace(ClasspathCronScheduleLoader.SOURCE, List.of());

        assertThat(cronScheduleRepository.findAll()).containsExactly(
                new CronFileSchedule("/srv/defs", 0, "cron-vars", new CronSchedule("@daily")));
    }
}
