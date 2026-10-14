package io.mateu.workflow.infra.in.scheduler;

import io.mateu.workflow.domain.aggregates.CronFileSchedule;
import io.mateu.workflow.domain.aggregates.CronSchedule;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.domain.aggregates.WorkflowStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import io.mateu.workflow.security.AuthorizationContext;
import io.mateu.workflow.time.Moments;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CronStartsTest {

    private final List<ProcessCreationRequested> fired = new ArrayList<>();
    private final Map<String, ZonedDateTime> lastFireTimes = new HashMap<>();

    @AfterEach
    void resetDefaultZone() {
        Moments.setDefaultZone(null);
    }

    private static WorkflowDefinition definition(String id, String cronExpression, List<CronSchedule> schedules) {
        return new WorkflowDefinition(id, id, 1, null, false, 0, false, cronExpression, 0,
                List.of())
                .withSchedules(schedules);
    }

    private void scan(List<WorkflowDefinition> definitions, List<CronFileSchedule> fileSchedules, String instant) {
        CronStarts.fireDue(definitions, fileSchedules, fired::add, lastFireTimes, Instant.parse(instant));
    }

    private static Map<String, String> vars(String... nameValues) {
        var map = new LinkedHashMap<String, String>();
        for (int i = 0; i < nameValues.length; i += 2) {
            map.put(nameValues[i], nameValues[i + 1]);
        }
        return map;
    }

    @Test
    void firstScanOnlyStartsTheClockSoMissedOccurrencesAreNotReplayed() {
        var defs = List.of(definition("daily", null, List.of(new CronSchedule("0 0 9 * * *", "UTC", null))));

        scan(defs, List.of(), "2026-10-14T12:00:00Z");

        assertThat(fired).isEmpty();
    }

    @Test
    void aScheduleStartsAProcessWithItsVariablesAndADistinctDeterministicKey() {
        var defs = List.of(definition("report", null, List.of(
                new CronSchedule("0 0 9 * * *", "Europe/Madrid", vars("region", "EMEA", "format", "pdf")),
                new CronSchedule("0 0 9 * * *", "America/New_York", vars("region", "AMER")))));

        scan(defs, List.of(), "2026-10-14T06:00:00Z");
        scan(defs, List.of(), "2026-10-14T07:00:01Z");   // 09:00 Madrid (UTC+2)

        assertThat(fired).hasSize(1);
        var start = fired.get(0);
        assertThat(start.workflowDefinitionId()).isEqualTo("report");
        assertThat(start.businessKey()).isEqualTo("report-cron-s0-2026-10-14T09:00:00+02:00");
        assertThat(start.variables()).containsExactly(new Variable("region", "EMEA"), new Variable("format", "pdf"));
        assertThat(start.caller()).isEqualTo(AuthorizationContext.SYSTEM);

        scan(defs, List.of(), "2026-10-14T13:00:01Z");   // 09:00 New York (UTC-4)

        assertThat(fired).hasSize(2);
        assertThat(fired.get(1).businessKey()).isEqualTo("report-cron-s1-2026-10-14T09:00:00-04:00");
        assertThat(fired.get(1).variables()).containsExactly(new Variable("region", "AMER"));
    }

    @Test
    void theLegacyCronExpressionKeepsItsKeyAndStartsWithoutVariables() {
        var zone = ZoneId.systemDefault();
        var t0 = LocalDateTime.of(2026, 10, 14, 10, 0, 0).atZone(zone).toInstant();
        var defs = List.of(definition("nightly", "0 30 10 * * *", List.of(new CronSchedule("0 30 10 * * *", "UTC", null))));

        CronStarts.fireDue(defs, List.of(), fired::add, lastFireTimes, t0);
        CronStarts.fireDue(defs, List.of(), fired::add, lastFireTimes, t0.plusSeconds(31 * 60));

        assertThat(fired).extracting(ProcessCreationRequested::businessKey)
                .contains("nightly-cron-2026-10-14T10:30:00");
        var legacy = fired.stream().filter(f -> f.businessKey().equals("nightly-cron-2026-10-14T10:30:00")).findFirst().orElseThrow();
        assertThat(legacy.variables()).isEmpty();
    }

    @Test
    void eachScheduleIsTrackedOnItsOwnSoBothFireEveryOccurrence() {
        var defs = List.of(definition("multi", null, List.of(
                new CronSchedule("0 * * * * *", "UTC", null),
                new CronSchedule("30 * * * * *", "UTC", null))));

        scan(defs, List.of(), "2026-10-14T12:00:01Z");
        scan(defs, List.of(), "2026-10-14T12:02:01Z");

        assertThat(fired).extracting(ProcessCreationRequested::businessKey).containsExactlyInAnyOrder(
                "multi-cron-s0-2026-10-14T12:01:00Z", "multi-cron-s0-2026-10-14T12:02:00Z",
                "multi-cron-s1-2026-10-14T12:00:30Z", "multi-cron-s1-2026-10-14T12:01:30Z");
    }

    @Test
    void aScheduleWithoutAZoneIsReadInTheEngineZone() {
        Moments.setDefaultZone(ZoneId.of("Asia/Tokyo"));
        var defs = List.of(definition("tokyo", null, List.of(new CronSchedule("0 0 9 * * *"))));

        scan(defs, List.of(), "2026-10-13T23:00:00Z");
        scan(defs, List.of(), "2026-10-14T00:00:01Z");   // 09:00 in Tokyo (UTC+9)

        assertThat(fired).extracting(ProcessCreationRequested::businessKey)
                .containsExactly("tokyo-cron-s0-2026-10-14T09:00:00+09:00");
    }

    @Test
    void cronFileEntriesStartTheirWorkflowWithAContentKeyAndOnlyOnceWhenDeclaredTwice() {
        var defs = List.of(definition("billing", null, null));
        var schedule = new CronSchedule("0 0 * * * *", "UTC", vars("cycle", "hourly"));
        var files = List.of(
                new CronFileSchedule("classpath:/workflows/cron.yaml", 0, "billing", schedule),
                new CronFileSchedule("/srv/defs", 0, "billing", schedule),
                new CronFileSchedule("/srv/defs", 1, "missing", new CronSchedule("0 0 * * * *", "UTC", null)));

        scan(defs, files, "2026-10-14T11:59:00Z");
        scan(defs, files, "2026-10-14T12:00:01Z");

        var hash = CronStarts.contentHash(schedule);
        assertThat(hash).hasSize(8);
        assertThat(fired).hasSize(1);
        assertThat(fired.get(0).businessKey()).isEqualTo("billing-cron-f" + hash + "-2026-10-14T12:00:00Z");
        assertThat(fired.get(0).variables()).containsExactly(new Variable("cycle", "hourly"));
    }

    @Test
    void disabledDefinitionsDoNotStartEvenFromCronFiles() {
        var disabled = definition("off", "0 * * * * *", List.of(new CronSchedule("0 * * * * *", "UTC", null)))
                .withRuntimeStatus(WorkflowStatus.DISABLED);
        var files = List.of(new CronFileSchedule("src", 0, "off", new CronSchedule("0 * * * * *", "UTC", null)));

        scan(List.of(disabled), files, "2026-10-14T12:00:01Z");
        scan(List.of(disabled), files, "2026-10-14T12:05:01Z");

        assertThat(fired).isEmpty();
        assertThat(lastFireTimes).isEmpty();
    }

    @Test
    void anInvalidScheduleIsSkippedWithoutStoppingTheOthers() {
        var defs = List.of(definition("mixed", "not a cron", List.of(
                new CronSchedule("0 * * * * *", "Mars/Olympus", null),
                new CronSchedule("0 * * * * *", "UTC", null))));

        scan(defs, List.of(), "2026-10-14T12:00:01Z");
        scan(defs, List.of(), "2026-10-14T12:01:01Z");

        assertThat(fired).extracting(ProcessCreationRequested::businessKey)
                .containsExactly("mixed-cron-s1-2026-10-14T12:01:00Z");
    }

    @Test
    void anEditedOrRemovedScheduleStopsBeingTrackedAndDoesNotReplay() {
        var hourly = List.of(definition("edit", null, List.of(new CronSchedule("0 0 * * * *", "UTC", null))));
        scan(hourly, List.of(), "2026-10-14T10:00:01Z");
        assertThat(lastFireTimes).hasSize(1);

        // Same index, new expression: a fresh clock, not "every minute since the hourly one last fired".
        var everyMinute = List.of(definition("edit", null, List.of(new CronSchedule("0 * * * * *", "UTC", null))));
        scan(everyMinute, List.of(), "2026-10-14T10:30:01Z");
        assertThat(fired).isEmpty();
        assertThat(lastFireTimes).hasSize(1);

        scan(List.of(definition("edit", null, null)), List.of(), "2026-10-14T10:31:01Z");
        assertThat(fired).isEmpty();
        assertThat(lastFireTimes).isEmpty();
    }

    @Test
    void nextFireIsComputedInTheScheduleZone() {
        var next = new CronSchedule("0 0 9 * * *", "Europe/Madrid", null).nextFire(Instant.parse("2026-10-14T08:00:00Z"));
        assertThat(next).hasValueSatisfying(n -> assertThat(n.toInstant()).isEqualTo(Instant.parse("2026-10-15T07:00:00Z")));
        assertThat(new CronSchedule("bad").nextFire(Instant.now())).isEmpty();
    }
}
