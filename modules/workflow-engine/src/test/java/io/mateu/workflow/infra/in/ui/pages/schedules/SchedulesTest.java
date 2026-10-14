package io.mateu.workflow.infra.in.ui.pages.schedules;

import io.mateu.uidl.data.Pageable;
import io.mateu.uidl.data.SearchRequest;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.services.ScheduleService;
import io.mateu.workflow.application.services.ScheduleService.ScheduleDraft;
import io.mateu.workflow.application.services.ScheduleService.ScheduleState;
import io.mateu.workflow.domain.aggregates.CronSchedule;
import io.mateu.workflow.domain.aggregates.ScheduleOrigin;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.infra.out.memory.InMemoryCronScheduleRepository;
import io.mateu.workflow.infra.out.memory.InMemorySchedulePauseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The Schedules page and a schedule's page, over the real service and in-memory stores. */
class SchedulesTest {

    final WorkflowDefinitionRepository definitions = mock(WorkflowDefinitionRepository.class);
    ScheduleService service;
    Schedules page;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(definitions.findAll()).thenReturn(List.of(
                new WorkflowDefinition("report", "Report", 1, null, false, 0, false, null, 0, List.of())
                        .withSchedules(List.of(new CronSchedule("eom", "0 0 23 L * ?", null, null))),
                new WorkflowDefinition("billing", "Billing", 1, null, false, 0, false, null, 0, List.of())));
        service = new ScheduleService(definitions, new InMemoryCronScheduleRepository(),
                new InMemorySchedulePauseRepository(), c -> { })
                .withClock(() -> Instant.parse("2026-10-14T10:00:00Z"));
        ObjectProvider<ScheduleDetailView> details = mock(ObjectProvider.class);
        when(details.getObject()).thenAnswer(i -> new ScheduleDetailView(service, null));
        page = new Schedules(service, details, null);
    }

    private List<ScheduleListRow> search(String text, ScheduleFilters filters, int page, int size) {
        return this.page.search(new SearchRequest(text, filters, null, new Pageable(page, size, List.of())), null)
                .page().content();
    }

    @Test
    void listsEverySchedulePagedAndFiltered() {
        service.create(new ScheduleDraft("nightly", "billing", "0 0 9 * * *", "UTC", Map.of()));
        service.create(new ScheduleDraft("weekly", "billing", "0 0 9 * * MON", "UTC", Map.of()));
        service.pause("weekly", null);

        assertThat(search(null, null, 0, 10)).extracting(ScheduleListRow::id).containsExactly("eom", "nightly", "weekly");
        assertThat(search(null, null, 1, 2)).extracting(ScheduleListRow::id).containsExactly("weekly");
        assertThat(search(null, new ScheduleFilters("report", null, null), 0, 10))
                .extracting(ScheduleListRow::id).containsExactly("eom");
        assertThat(search(null, new ScheduleFilters(null, ScheduleOrigin.API, null), 0, 10))
                .extracting(ScheduleListRow::id).containsExactly("nightly", "weekly");
        assertThat(search(null, new ScheduleFilters(null, null, ScheduleState.PAUSED), 0, 10))
                .extracting(ScheduleListRow::id).containsExactly("weekly");
        assertThat(search("MON", null, 0, 10)).extracting(ScheduleListRow::id).containsExactly("weekly");

        var nightly = search("nightly", null, 0, 10).get(0);
        assertThat(nightly.description()).isEqualTo("Every day at 09:00");
        assertThat(nightly.nextFire()).isEqualTo("2026-10-15 09:00:00 +00:00 (UTC)");
        assertThat(nightly.source()).isEqualTo("UI/API");
        assertThat(nightly.status().message()).isEqualTo("Active");
    }

    @Test
    void createsAndEditsFromTheForm() {
        var form = new ScheduleForm();
        form.id = "nightly";
        form.workflowId = "billing";
        form.cron = "0 0 9 * * *";
        form.variables = List.of(new Variable("region", "EMEA"));
        var request = mock(HttpRequest.class);
        when(request.getComponentState(ScheduleForm.class)).thenReturn(form);

        assertThat(page.create(request)).isEqualTo("nightly");

        var editor = page.edit("nightly", null);
        assertThat(editor.id).isEqualTo("nightly");
        assertThat(editor.variables).containsExactly(new Variable("region", "EMEA"));
        editor.cron = "@hourly";
        when(request.getComponentState(ScheduleForm.class)).thenReturn(editor);
        assertThat(page.save(request)).isEqualTo("nightly");
        assertThat(service.get("nightly").cron()).isEqualTo("@hourly");
    }

    @Test
    void bulkPauseAndResume() {
        var eom = search("eom", null, 0, 10);

        page.pauseSelected(eom);
        assertThat(service.get("eom").paused()).isTrue();
        page.resumeSelected(eom);
        assertThat(service.get("eom").paused()).isFalse();
    }

    @Test
    void aSchedulesPageOffersEditingOnlyForWhatWasCreatedHere() {
        service.create(new ScheduleDraft("nightly", "billing", "0 0 9 * * *", "UTC", Map.of("region", "EMEA")));

        var created = (ScheduleDetailView) page.view("nightly", null);
        assertThat(created.toString()).isEqualTo("Schedule nightly");
        assertThat(created.isHidden("edit", null)).isFalse();
        assertThat(created.isHidden("deleteSchedule", null)).isFalse();
        assertThat(created.isHidden("delete", null)).as("the Crud's own delete is for listing selections").isTrue();
        assertThat(created.isHidden("pause", null)).isFalse();
        assertThat(created.isHidden("resume", null)).isTrue();
        assertThat(created.variables).isEqualTo("region=EMEA");
        assertThat(created.details.html()).isTrue();
        assertThat(created.details.content())
                .contains("<ul><li>2026-10-15 09:00:00 +00:00 (UTC)</li>")
                .contains("<p>Paste this entry")
                // escaped: the YAML quotes its cron
                .contains("- id: nightly\n  cron: &quot;0 0 9 * * *&quot;");

        service.pause("eom", "ops");
        var fileOwned = (ScheduleDetailView) page.view("eom", null);
        assertThat(fileOwned.isHidden("edit", null)).isTrue();
        assertThat(fileOwned.isHidden("deleteSchedule", null)).isTrue();
        assertThat(fileOwned.isHidden("pause", null)).isTrue();
        assertThat(fileOwned.isHidden("resume", null)).isFalse();
        assertThat(fileOwned.status.message()).isEqualTo("Paused");
        assertThat(fileOwned.pausedInfo).isEqualTo("since 2026-10-14T10:00:00Z by ops");
        assertThat(fileOwned.ownership).startsWith("Declared in a file");
        assertThat(fileOwned.source).isEqualTo("definition (schedules[0])");
    }

    @Test
    void htmlInWhatASchedulesPageShowsIsEscaped() {
        assertThat(ScheduleFormats.escape("<b>\"x\" & 'y'</b>")).isEqualTo("&lt;b&gt;&quot;x&quot; &amp; &#39;y&#39;&lt;/b&gt;");
        assertThat(ScheduleFormats.pathSegment("report~fab12-x.y_z")).isEqualTo("report~fab12-x.y_z");
        assertThat(ScheduleFormats.pathSegment("a b/ñ")).isEqualTo("a%20b%2F%C3%B1");
    }
}
