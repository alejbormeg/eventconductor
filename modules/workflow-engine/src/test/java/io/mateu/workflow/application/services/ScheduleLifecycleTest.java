package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.services.ScheduleService.ScheduleDraft;
import io.mateu.workflow.application.services.ScheduleService.ScheduleNotEditableException;
import io.mateu.workflow.application.services.ScheduleService.UnknownScheduleException;
import io.mateu.workflow.domain.aggregates.CronSchedule;
import io.mateu.workflow.domain.aggregates.Schedule;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.infra.out.memory.InMemoryCronScheduleRepository;
import io.mateu.workflow.infra.out.memory.InMemorySchedulePauseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The life of a schedule: created from the UI, tried, promoted to git — and from then on owned by the
 * file. One schedule per id throughout; the file's import is what hands it over, and what deletes it.
 */
class ScheduleLifecycleTest {

    static final String GIT = "https://git.example.com/acme/definitions.git";

    final WorkflowDefinitionRepository definitions = mock(WorkflowDefinitionRepository.class);
    final InMemoryCronScheduleRepository schedules = new InMemoryCronScheduleRepository();
    final InMemorySchedulePauseRepository pauses = new InMemorySchedulePauseRepository();
    final CronScheduleImporter importer = new CronScheduleImporter(definitions, schedules, pauses);
    final ScheduleService service = new ScheduleService(definitions, schedules, pauses, c -> { })
            .withClock(() -> Instant.parse("2026-10-14T10:00:00Z"));
    final List<WorkflowDefinition> known = new ArrayList<>(List.of(definition("report"), definition("billing")));

    static WorkflowDefinition definition(String id) {
        return new WorkflowDefinition(id, id, 1, null, false, 0, false, null, 0, List.of());
    }

    @BeforeEach
    void knownWorkflows() {
        when(definitions.findAll()).thenAnswer(i -> List.copyOf(known));
        when(definitions.findById(anyString())).thenAnswer(i ->
                known.stream().filter(d -> d.id().equals(i.getArgument(0))).findFirst().map(Optional::of).orElse(Optional.empty()));
    }

    private CronScheduleImporter.Result importGit(String yaml) {
        return importer.importSource(GIT, List.of(new CronScheduleImporter.CronFile("cron.yaml",
                yaml.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void createdInTheUiPromotedToGitThenOwnedByTheFile() {
        // 1. Created and tried from the UI.
        service.create(new ScheduleDraft("nightly", "report", "0 0 9 * * *", "UTC", null));
        service.pause("nightly", "ops");
        assertThat(service.get("nightly").sourceLabel()).isEqualTo("UI/API");

        // 2. Promoted: its YAML committed — with a different cron, even — and imported.
        var yaml = service.exportYaml("nightly").replace("0 0 9 * * *", "0 30 21 * * *");
        var result = importGit(yaml);

        // 3. One schedule, now git's: the file's values, the file's source, the pause carried over.
        assertThat(result.errors()).isEmpty();
        var owned = service.get("nightly");
        assertThat(owned.origin()).isEqualTo("CRON_FILE");
        assertThat(owned.source()).isEqualTo(GIT);
        assertThat(owned.sourceLabel()).isEqualTo("cron.yaml (git)");
        assertThat(owned.cron()).isEqualTo("0 30 21 * * *");
        assertThat(owned.editable()).isFalse();
        assertThat(owned.paused()).isTrue();
        assertThat(service.list(null, null)).hasSize(1);
        assertThat(schedules.findAll()).noneMatch(s -> Schedule.API_SOURCE.equals(s.source()));
        assertThatThrownBy(() -> service.update("nightly", new ScheduleDraft("nightly", "report", "@daily", null, null)))
                .isInstanceOf(ScheduleNotEditableException.class);

        // 4. Gone from git: deleted, pause included — not handed back to the UI.
        importGit("[]");
        assertThatThrownBy(() -> service.get("nightly")).isInstanceOf(UnknownScheduleException.class);
        assertThat(pauses.findAll()).isEmpty();
    }

    @Test
    void anImportNeverTouchesTheUiSchedulesNoFileClaims() {
        service.create(new ScheduleDraft("adhoc", "report", "@daily", null, null));
        service.pause("adhoc", null);

        importGit("""
                - id: other
                  cron: "@hourly"
                  workflow: billing
                """);
        importGit("[]");

        assertThat(service.get("adhoc").sourceLabel()).isEqualTo("UI/API");
        assertThat(service.get("adhoc").paused()).isTrue();
    }

    @Test
    void aDefinitionDeclaringTheIdTakesItOverToo() {
        service.create(new ScheduleDraft("eom", "billing", "@daily", null, null));
        known.set(1, definition("billing").withSchedules(List.of(new CronSchedule("eom", "0 0 23 L * ?", null, null))));

        // Any import reconciles: here the classpath's, with no cron.yaml at all.
        importer.importSource("classpath:/workflows/cron.yaml", List.of());

        assertThat(service.get("eom").origin()).isEqualTo("DEFINITION");
        assertThat(schedules.findAll()).isEmpty();
    }

    @Test
    void aPauseOfAFileOwnedScheduleSurvivesReimportsAndGoesWithIt() {
        importGit("""
                - cron: "0 0 6 * * *"
                  workflow: report
                """);
        var id = service.list(null, null).get(0).id();
        assertThat(id).startsWith("report~f");
        service.pause(id, "ops");

        importGit("""
                - cron: "0 0 6 * * *"
                  workflow: report
                """);
        assertThat(service.get(id).paused()).isTrue();

        importGit("""
                - cron: "0 0 7 * * *"
                  workflow: report
                """);
        assertThat(pauses.findAll()).as("edited without an id it is another schedule; the old one is gone").isEmpty();
    }

    @Test
    void anIdDeclaredTwiceInOneSourceKeepsTheFirst() {
        var result = importGit("""
                - id: twice
                  cron: "@daily"
                  workflow: report
                - id: twice
                  cron: "@hourly"
                  workflow: report
                """);

        assertThat(result.errors()).containsExactly(
                "cron.yaml: schedule id 'twice' is declared more than once in " + GIT + " — the later entry is skipped");
        assertThat(service.get("twice").cron()).isEqualTo("@daily");
    }

    @Test
    void noFileIsEverImportedIntoTheEnginesOwnSource() {
        assertThatThrownBy(() -> importer.importSource(Schedule.API_SOURCE, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> schedules.replace(Schedule.API_SOURCE, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anInvalidIdInACronFileIsReportedAndSkipped() {
        var result = importGit("""
                - id: "no spaces"
                  cron: "@daily"
                  workflow: report
                """);

        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0)).contains("'no spaces' is not a valid schedule id");
        assertThat(service.list(null, null)).isEmpty();
    }
}
