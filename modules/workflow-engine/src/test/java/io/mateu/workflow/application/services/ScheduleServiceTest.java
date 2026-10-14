package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.services.ScheduleService.InvalidScheduleException;
import io.mateu.workflow.application.services.ScheduleService.ScheduleDraft;
import io.mateu.workflow.application.services.ScheduleService.ScheduleNotEditableException;
import io.mateu.workflow.application.services.ScheduleService.ScheduleState;
import io.mateu.workflow.application.services.ScheduleService.UnknownScheduleException;
import io.mateu.workflow.domain.aggregates.CronFileSchedule;
import io.mateu.workflow.domain.aggregates.CronSchedule;
import io.mateu.workflow.domain.aggregates.Schedule;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.domain.aggregates.WorkflowStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import io.mateu.workflow.infra.out.memory.InMemoryCronScheduleRepository;
import io.mateu.workflow.infra.out.memory.InMemorySchedulePauseRepository;
import io.mateu.workflow.security.AuthorizationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScheduleServiceTest {

    static final Instant NOW = Instant.parse("2026-10-14T10:00:00Z");

    final WorkflowDefinitionRepository definitions = mock(WorkflowDefinitionRepository.class);
    final InMemoryCronScheduleRepository schedules = new InMemoryCronScheduleRepository();
    final InMemorySchedulePauseRepository pauses = new InMemorySchedulePauseRepository();
    final List<ProcessCreationRequested> started = new ArrayList<>();
    final ScheduleService service = new ScheduleService(definitions, schedules, pauses, started::add).withClock(() -> NOW);

    static WorkflowDefinition definition(String id) {
        return new WorkflowDefinition(id, "The " + id, 1, null, false, 0, false, null, 0, List.of());
    }

    @BeforeEach
    void someWorkflows() {
        when(definitions.findAll()).thenReturn(List.of(
                definition("report"),
                definition("billing").withSchedules(List.of(new CronSchedule("eom", "0 0 23 L * ?", null, null))),
                definition("retired").withRuntimeStatus(WorkflowStatus.DISABLED)));
    }

    static Map<String, String> vars(String... nameValues) {
        var map = new LinkedHashMap<String, String>();
        for (int i = 0; i < nameValues.length; i += 2) {
            map.put(nameValues[i], nameValues[i + 1]);
        }
        return map;
    }

    @Test
    void createsAScheduleOwnedByTheEngineAndShowsItsNextStarts() {
        var view = service.create(new ScheduleDraft(" nightly ", "report", "0 0 9 * * MON-FRI", "Europe/Madrid",
                vars("region", "EMEA")));

        assertThat(view.id()).isEqualTo("nightly");
        assertThat(view.origin()).isEqualTo("API");
        assertThat(view.sourceLabel()).isEqualTo("UI/API");
        assertThat(view.editable()).isTrue();
        assertThat(view.state()).isEqualTo(ScheduleState.ACTIVE);
        assertThat(view.workflowName()).isEqualTo("The report");
        assertThat(view.description()).isEqualTo("MON-FRI at 09:00");
        assertThat(view.nextFires()).hasSize(ScheduleService.NEXT_FIRES)
                .first().isEqualTo("2026-10-15T09:00:00+02:00[Europe/Madrid]");
        assertThat(schedules.findAll()).containsExactly(new CronFileSchedule(Schedule.API_SOURCE, 0, "report",
                new CronSchedule("nightly", "0 0 9 * * MON-FRI", "Europe/Madrid", vars("region", "EMEA"))));
    }

    @Test
    void refusesWhatBreaksARuleSayingEveryRule() {
        assertThatThrownBy(() -> service.create(new ScheduleDraft("bad id", "retired", "0 9 * * *", "Mars/Olympus",
                vars("", "x"))))
                .isInstanceOf(InvalidScheduleException.class)
                .satisfies(e -> assertThat(((InvalidScheduleException) e).violations()).containsExactly(
                        "workflow 'retired' is disabled and does not accept new instances",
                        "'bad id' is not a valid schedule id (letters, digits, '.', '_' and '-', starting with a letter or digit, at most 100 characters)",
                        "'0 9 * * *' is not a valid cron expression (six fields, seconds first, e.g. \"0 0 9 * * MON-FRI\")",
                        "'Mars/Olympus' is not a valid time zone (an IANA id such as Europe/Madrid)",
                        "a variable needs a name"));
        assertThatThrownBy(() -> service.create(new ScheduleDraft(null, "nope", "@daily", null, null)))
                .isInstanceOf(InvalidScheduleException.class)
                .hasMessage("a schedule created here needs an id (e.g. nightly-report); workflow 'nope' is unknown");
        assertThat(schedules.findAll()).isEmpty();
    }

    @Test
    void anIdIsOneScheduleSoATakenOneIsRefused() {
        service.create(new ScheduleDraft("nightly", "report", "@daily", null, null));

        assertThatThrownBy(() -> service.create(new ScheduleDraft("nightly", "report", "@hourly", null, null)))
                .hasMessage("id 'nightly' is already used by the schedule declared in UI/API");
        assertThatThrownBy(() -> service.create(new ScheduleDraft("eom", "report", "@hourly", null, null)))
                .hasMessage("id 'eom' is already used by the schedule declared in definition (schedules[0])");
    }

    @Test
    void editsAndDeletesOnlyWhatWasCreatedHere() {
        service.create(new ScheduleDraft("nightly", "report", "@daily", null, null));
        service.pause("nightly", "ops");

        var edited = service.update("nightly", new ScheduleDraft("ignored", "billing", "0 30 6 * * *", "UTC", vars("x", "1")));

        assertThat(edited.id()).isEqualTo("nightly");
        assertThat(edited.workflowId()).isEqualTo("billing");
        assertThat(edited.cron()).isEqualTo("0 30 6 * * *");
        assertThat(edited.variables()).containsExactly(Map.entry("x", "1"));
        assertThat(edited.paused()).as("an edit keeps the pause: it is kept by id").isTrue();

        assertThatThrownBy(() -> service.update("eom", new ScheduleDraft("eom", "billing", "@daily", null, null)))
                .isInstanceOf(ScheduleNotEditableException.class)
                .hasMessageContaining("declared in definition (schedules[0]): change it there");
        assertThatThrownBy(() -> service.delete("eom")).isInstanceOf(ScheduleNotEditableException.class);

        service.delete("nightly");
        assertThat(schedules.findAll()).isEmpty();
        assertThat(pauses.findAll()).isEmpty();
        assertThatThrownBy(() -> service.get("nightly")).isInstanceOf(UnknownScheduleException.class);
    }

    @Test
    void pausesAndResumesAnySchedule() {
        var paused = service.pause("eom", "ops");

        assertThat(paused.paused()).isTrue();
        assertThat(paused.state()).isEqualTo(ScheduleState.PAUSED);
        assertThat(paused.pausedBy()).isEqualTo("ops");
        assertThat(paused.pausedAt()).isEqualTo("2026-10-14T10:00:00Z");
        assertThat(service.pause("eom", "someone else").pausedBy()).as("the first pause stands").isEqualTo("ops");

        var resumed = service.resume("eom");
        assertThat(resumed.paused()).isFalse();
        assertThat(resumed.state()).isEqualTo(ScheduleState.ACTIVE);
        assertThatThrownBy(() -> service.pause("nope", null)).isInstanceOf(UnknownScheduleException.class);
    }

    @Test
    void runNowStartsOneProcessThroughTheSchedulersDoorEvenWhenPaused() {
        service.create(new ScheduleDraft("nightly", "report", "@daily", null, vars("region", "EMEA")));
        service.pause("nightly", null);

        var result = service.runNow("nightly", "ops");

        assertThat(result.businessKey()).isEqualTo("report-cron-manual-nightly-2026-10-14T10:00:00.000Z");
        assertThat(started).containsExactly(new ProcessCreationRequested("report", result.businessKey(),
                List.of(new Variable("region", "EMEA")), null, AuthorizationContext.SYSTEM));
    }

    @Test
    void runNowRefusesAWorkflowThatTakesNoInstances() {
        schedules.save(new CronFileSchedule(Schedule.API_SOURCE, 0, "retired", new CronSchedule("old", "@daily", null, null)));

        assertThat(service.get("old").state()).isEqualTo(ScheduleState.WORKFLOW_INACTIVE);
        assertThatThrownBy(() -> service.runNow("old", null)).isInstanceOf(InvalidScheduleException.class);
        assertThat(started).isEmpty();
    }

    @Test
    void listsOneSchedulePerIdFilteredByWorkflowAndText() {
        service.create(new ScheduleDraft("nightly", "report", "@daily", null, vars("region", "EMEA")));
        schedules.replace("/srv/defs", List.of(new CronFileSchedule("/srv/defs", 0, "report",
                new CronSchedule("eom", "@hourly", null, null))));

        assertThat(service.list(null, null)).extracting(ScheduleService.ScheduleView::id).containsExactly("eom", "nightly");
        assertThat(service.get("eom").alsoDeclaredIn()).containsExactly("cron.yaml (directory) /srv/defs");
        assertThat(service.list("report", null)).extracting(ScheduleService.ScheduleView::id).containsExactly("nightly");
        assertThat(service.list(null, "emea")).extracting(ScheduleService.ScheduleView::id).containsExactly("nightly");
        assertThat(service.list(null, "git")).isEmpty();
    }

    @Test
    void exportsACronYamlEntryTheImporterReadsBackAsTheSameSchedule() {
        service.create(new ScheduleDraft("nightly", "report", "@daily", "Europe/Madrid",
                vars("region", "EMEA", "copies", "3", "note", "a: \"b\"", "flag", "true")));

        var yaml = service.exportYaml("nightly");

        assertThat(yaml).contains("- id: nightly\n  cron: \"@daily\"\n  workflow: report\n  zone: Europe/Madrid\n");
        var parsed = CronScheduleImporter.parse("cron.yaml", yaml.getBytes(StandardCharsets.UTF_8));
        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.entries()).containsExactly(new CronScheduleImporter.Entry("report", new CronSchedule(
                "nightly", "@daily", "Europe/Madrid", vars("region", "EMEA", "copies", "3", "note", "a: \"b\"", "flag", "true"))));
    }

    @Test
    void previewsTheNextStartsOfACron() {
        assertThat(service.preview("0 0 9 * * *", "UTC", 2))
                .containsExactly("2026-10-15T09:00:00Z[UTC]", "2026-10-16T09:00:00Z[UTC]");
        assertThatThrownBy(() -> service.preview("nope", null, 2)).isInstanceOf(InvalidScheduleException.class);
    }
}
