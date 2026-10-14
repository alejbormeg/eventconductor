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
    @Autowired io.mateu.workflow.application.out.SchedulePauseRepository schedulePauseRepository;
    @Autowired io.mateu.workflow.application.services.ScheduleService scheduleService;
    @Autowired io.mateu.workflow.application.services.CronScheduleImporter cronScheduleImporter;

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

    /**
     * E2E-CRON-04 — a schedule created through the engine is a row of its own (source {@code ui}) that no
     * import replaces, until a file declares its id: then the file's entry is the schedule, the row is
     * gone and the pause, kept by id in {@code cron_schedule_pause}, carries over. Gone from the file,
     * it is gone with its pause.
     */
    @Test
    void engineCreatedSchedulesAndPausesAreStoredAndHandedOverToTheFile() {
        assertThat(schedulePauseRepository).isInstanceOf(io.mateu.workflow.infra.out.persistence.JpaSchedulePauseRepository.class);
        scheduleService.create(new io.mateu.workflow.application.services.ScheduleService.ScheduleDraft(
                "nightly", "cron-vars", "0 0 9 * * *", "UTC", Map.of("region", "EMEA")));
        scheduleService.create(new io.mateu.workflow.application.services.ScheduleService.ScheduleDraft(
                "adhoc", "cron-vars", "@daily", null, null));
        scheduleService.pause("nightly", "ops");

        assertThat(cronScheduleRepository.findAll()).filteredOn(s -> "ui".equals(s.source()))
                .extracting(s -> s.schedule().id()).containsExactlyInAnyOrder("nightly", "adhoc");
        assertThat(scheduleService.get("nightly").variables()).containsExactly(Map.entry("region", "EMEA"));
        assertThat(schedulePauseRepository.findById("nightly")).hasValueSatisfying(p -> assertThat(p.pausedBy()).isEqualTo("ops"));

        var git = "https://git.example.com/defs.git";
        cronScheduleImporter.importSource(git, List.of(new io.mateu.workflow.application.services.CronScheduleImporter.CronFile(
                "cron.yaml", scheduleService.exportYaml("nightly").replace("0 0 9 * * *", "0 0 21 * * *")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))));

        var owned = scheduleService.get("nightly");
        assertThat(owned.source()).isEqualTo(git);
        assertThat(owned.cron()).isEqualTo("0 0 21 * * *");
        assertThat(owned.paused()).isTrue();
        assertThat(cronScheduleRepository.findAll()).filteredOn(s -> "ui".equals(s.source()))
                .extracting(s -> s.schedule().id()).containsExactly("adhoc");

        cronScheduleImporter.importSource(git, List.of());

        assertThat(scheduleService.list(null, null)).extracting(v -> v.id()).doesNotContain("nightly").contains("adhoc");
        assertThat(schedulePauseRepository.findAll()).isEmpty();
    }
}
