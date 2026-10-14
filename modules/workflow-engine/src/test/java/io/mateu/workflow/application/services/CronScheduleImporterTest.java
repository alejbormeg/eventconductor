package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.CronFileSchedule;
import io.mateu.workflow.domain.aggregates.CronSchedule;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.infra.out.memory.InMemoryCronScheduleRepository;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CronScheduleImporterTest {

    private final WorkflowDefinitionRepository definitions = mock(WorkflowDefinitionRepository.class);
    private final InMemoryCronScheduleRepository repository = new InMemoryCronScheduleRepository();
    private final CronScheduleImporter importer = new CronScheduleImporter(definitions, repository, new io.mateu.workflow.infra.out.memory.InMemorySchedulePauseRepository());

    private static byte[] yaml(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private void known(String... ids) {
        when(definitions.findById(anyString())).thenReturn(Optional.empty());
        for (var id : ids) {
            when(definitions.findById(id)).thenReturn(Optional.of(
                    new WorkflowDefinition(id, id, 1, null, false, 0, false, null, 0, List.of())));
        }
    }

    @Test
    void parsesABareListWithZoneAndVariablesReadAsText() {
        var parsed = CronScheduleImporter.parse("cron.yaml", yaml("""
                - cron: "0 0 9 * * MON-FRI"
                  workflow: daily-report
                  zone: Europe/Madrid
                  variables:
                    region: EMEA
                    copies: 3
                    urgent: true
                - cron: "@hourly"
                  workflow: sweep
                """));

        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.unreadable()).isFalse();
        assertThat(parsed.entries()).containsExactly(
                new CronScheduleImporter.Entry("daily-report", new CronSchedule("0 0 9 * * MON-FRI", "Europe/Madrid",
                        Map.of("region", "EMEA", "copies", "3", "urgent", "true"))),
                new CronScheduleImporter.Entry("sweep", new CronSchedule("@hourly")));
        assertThat(parsed.entries().get(0).schedule().variables().keySet()).containsExactly("region", "copies", "urgent");
    }

    @Test
    void acceptsATopLevelSchedulesKeyAndEmptyFiles() {
        var parsed = CronScheduleImporter.parse("cron.yaml", yaml("""
                schedules:
                  - cron: "0 0 3 * * *"
                    workflow: nightly
                """));
        assertThat(parsed.entries()).extracting(CronScheduleImporter.Entry::workflow).containsExactly("nightly");

        assertThat(CronScheduleImporter.parse("cron.yaml", yaml("")).entries()).isEmpty();
        assertThat(CronScheduleImporter.parse("cron.yaml", yaml("schedules:\n")).unreadable()).isFalse();
    }

    @Test
    void reportsAndSkipsBadEntriesButKeepsTheGoodOnes() {
        var parsed = CronScheduleImporter.parse("cron.yaml", yaml("""
                - cron: "0 9 * * *"
                  workflow: five-fields
                - cron: "0 0 9 * * *"
                  workflow: bad-zone
                  zone: Mars/Olympus
                - cron: "0 0 9 * * *"
                - cron: "0 0 9 * * *"
                  workflow: typo
                  varaibles: {a: 1}
                - cron: "0 0 9 * * *"
                  workflow: nested
                  variables: {a: {b: 1}}
                - just a string
                - cron: "0 0 9 * * *"
                  workflow: good
                """));

        assertThat(parsed.entries()).extracting(CronScheduleImporter.Entry::workflow).containsExactly("good");
        assertThat(parsed.errors()).hasSize(6).anySatisfy(e -> assertThat(e).contains("entry 0").contains("not a valid cron"))
                .anySatisfy(e -> assertThat(e).contains("entry 1").contains("not a valid time zone"))
                .anySatisfy(e -> assertThat(e).contains("entry 2").contains("'workflow'"))
                .anySatisfy(e -> assertThat(e).contains("entry 3").contains("unknown field 'varaibles'"))
                .anySatisfy(e -> assertThat(e).contains("entry 4").contains("variable 'a'"))
                .anySatisfy(e -> assertThat(e).contains("entry 5"));
    }

    @Test
    void aFileOfTheWrongShapeIsUnreadable() {
        assertThat(CronScheduleImporter.parse("cron.yaml", yaml("cron: x\nworkflow: y\n")).unreadable()).isTrue();
        assertThat(CronScheduleImporter.parse("cron.yaml", yaml("[unclosed")).unreadable()).isTrue();
    }

    @Test
    void importReplacesTheSourceAndSkipsUnknownWorkflows() {
        known("a", "b");
        repository.replace("other", List.of(new CronFileSchedule("other", 0, "b", new CronSchedule("@daily"))));
        repository.replace("src", List.of(new CronFileSchedule("src", 0, "a", new CronSchedule("@weekly"))));

        var result = importer.importSource("src", List.of(new CronScheduleImporter.CronFile("cron.yaml", yaml("""
                - cron: "@hourly"
                  workflow: a
                - cron: "@hourly"
                  workflow: ghost
                - cron: "@daily"
                  workflow: b
                  variables: {k: v}
                """))));

        assertThat(result.errors()).singleElement().asString().contains("unknown workflow 'ghost'");
        assertThat(repository.findAll()).containsExactly(
                new CronFileSchedule("other", 0, "b", new CronSchedule("@daily")),
                new CronFileSchedule("src", 0, "a", new CronSchedule("@hourly")),
                new CronFileSchedule("src", 1, "b", new CronSchedule("@daily", null, Map.of("k", "v"))));
        assertThat(repository.findByWorkflowId("b")).hasSize(2);

        // The file is gone: the source is cleared, the other source untouched.
        importer.importSource("src", List.of());
        assertThat(repository.findAll()).extracting(CronFileSchedule::source).containsExactly("other");
    }

    @Test
    void anUnreadableFileLeavesThePreviousSchedulesInPlace() {
        known("a");
        repository.replace("src", List.of(new CronFileSchedule("src", 0, "a", new CronSchedule("@weekly"))));

        var result = importer.importSource("src", List.of(new CronScheduleImporter.CronFile("cron.yaml", yaml("{{{"))));

        assertThat(result.errors()).isNotEmpty();
        assertThat(repository.findAll()).extracting(s -> s.schedule().cron()).containsExactly("@weekly");
    }
}
