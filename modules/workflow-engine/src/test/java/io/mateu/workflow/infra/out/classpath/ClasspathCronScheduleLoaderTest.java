package io.mateu.workflow.infra.out.classpath;

import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.services.CronScheduleImporter;
import io.mateu.workflow.application.services.DefinitionFileFormat;
import io.mateu.workflow.domain.aggregates.CronFileSchedule;
import io.mateu.workflow.domain.aggregates.CronSchedule;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.infra.out.memory.InMemoryCronScheduleRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClasspathCronScheduleLoaderTest {

    private final WorkflowDefinitionRepository definitions = mock(WorkflowDefinitionRepository.class);
    private final InMemoryCronScheduleRepository schedules = new InMemoryCronScheduleRepository();
    private final ClasspathCronScheduleLoader loader =
            new ClasspathCronScheduleLoader(new CronScheduleImporter(definitions, schedules));

    {
        when(definitions.findById(anyString())).thenReturn(Optional.empty());
        when(definitions.findById("daily-report")).thenReturn(Optional.of(
                new WorkflowDefinition("daily-report", "Daily report", 1, null, false, 0, false, null, 0, List.of())));
    }

    @Test
    void loadsABareListSkippingEntriesForUnknownWorkflows() {
        var result = loader.load("classpath:/cron-fixtures/listed/");

        assertThat(result.errors()).singleElement().asString().contains("unknown workflow 'unknown-workflow'");
        assertThat(schedules.findAll()).containsExactly(new CronFileSchedule(ClasspathCronScheduleLoader.SOURCE, 0,
                "daily-report", new CronSchedule("0 0 9 * * MON-FRI", "Europe/Madrid", Map.of("region", "EMEA"))));
    }

    @Test
    void loadsTheSchedulesKeyFormAndReplacesWhatThePreviousLoadLeft() {
        loader.load("classpath:/cron-fixtures/listed/");

        loader.load("classpath:/cron-fixtures/keyed/");

        assertThat(schedules.findAll()).extracting(s -> s.schedule().cron()).containsExactly("@hourly");
    }

    @Test
    void noFileClearsTheClasspathSource() {
        loader.load("classpath:/cron-fixtures/listed/");

        var result = loader.load("classpath:/cron-fixtures/none/");

        assertThat(result.errors()).isEmpty();
        assertThat(schedules.findAll()).isEmpty();
    }

    @Test
    void aCronFileIsNeverTakenForAWorkflowDefinition() {
        assertThat(DefinitionFileFormat.isDefinitionFileName("workflows/cron.yaml")).isFalse();
        assertThat(DefinitionFileFormat.isDefinitionFileName("cron.yml")).isFalse();
        assertThat(DefinitionFileFormat.isDefinitionFileName("nightly-cron.yaml")).isTrue();
        assertThat(DefinitionFileFormat.isDefinitionFileName("cron.json")).isTrue();
    }
}
