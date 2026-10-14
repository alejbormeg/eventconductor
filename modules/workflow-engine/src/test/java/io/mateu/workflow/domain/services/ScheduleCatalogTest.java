package io.mateu.workflow.domain.services;

import io.mateu.workflow.domain.aggregates.CronFileSchedule;
import io.mateu.workflow.domain.aggregates.CronSchedule;
import io.mateu.workflow.domain.aggregates.Schedule;
import io.mateu.workflow.domain.aggregates.ScheduleOrigin;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduleCatalogTest {

    private static WorkflowDefinition definition(String id, String cronExpression, CronSchedule... schedules) {
        return new WorkflowDefinition(id, id, 1, null, false, 0, false, cronExpression, 0, List.of())
                .withSchedules(List.of(schedules));
    }

    private static CronFileSchedule api(String id, String workflow, String cron) {
        return new CronFileSchedule(Schedule.API_SOURCE, 0, workflow, new CronSchedule(id, cron, null, null));
    }

    @Test
    void derivesAStableIdForEveryScheduleThatDeclaresNone() {
        var daily = new CronSchedule("0 0 9 * * *", "UTC", Map.of("a", "1"));
        var catalog = ScheduleCatalog.of(
                List.of(definition("report", "0 0 3 * * *", daily, daily)),
                List.of(new CronFileSchedule("/srv/defs", 0, "report", daily)));

        var hash = Schedule.contentHash(daily);
        assertThat(catalog).extracting(Schedule::id).containsExactly(
                "report~cron", "report~s" + hash, "report~s" + hash + "~2", "report~f" + hash);
        assertThat(catalog).noneMatch(Schedule::idDeclared);
        assertThat(catalog).allMatch(s -> s.duplicateOf() == null);
        // The ids are the same on every import: the content says what the schedule is.
        assertThat(ScheduleCatalog.of(List.of(definition("report", "0 0 3 * * *", daily, daily)),
                List.of(new CronFileSchedule("/elsewhere", 7, "report", daily))))
                .extracting(Schedule::id).last().isEqualTo("report~f" + hash);
    }

    @Test
    void anUnnamedSchedulesKeysAreTheOnesItAlwaysHad() {
        var schedule = new CronSchedule("0 0 9 * * *", "UTC", null);
        var catalog = ScheduleCatalog.of(List.of(definition("report", "0 0 3 * * *", schedule)),
                List.of(new CronFileSchedule("/srv/defs", 0, "report", schedule)));
        var at = ZonedDateTime.of(2026, 10, 14, 9, 0, 0, 0, ZoneOffset.UTC);

        assertThat(catalog.get(0).businessKey(at.withZoneSameInstant(java.time.ZoneId.systemDefault())))
                .startsWith("report-cron-2026-10-14T");
        assertThat(catalog.get(0).trackingKey()).isEqualTo("report#legacy#0 0 3 * * *");
        assertThat(catalog.get(1).businessKey(at)).isEqualTo("report-cron-s0-2026-10-14T09:00:00Z");
        assertThat(catalog.get(2).businessKey(at))
                .isEqualTo("report-cron-f" + Schedule.contentHash(schedule) + "-2026-10-14T09:00:00Z");
    }

    @Test
    void aDeclaredIdNamesTheScheduleWhoeverOwnsIt() {
        var at = ZonedDateTime.of(2026, 10, 14, 9, 0, 0, 0, ZoneOffset.UTC);
        var fromUi = ScheduleCatalog.of(List.of(definition("report", null)), List.of(api("nightly", "report", "0 0 9 * * *")));
        var fromGit = ScheduleCatalog.of(List.of(definition("report", null)), List.of(new CronFileSchedule(
                "https://git.example.com/defs.git", 0, "report", new CronSchedule("nightly", "0 0 9 * * *", null, null))));

        assertThat(fromUi.get(0).origin()).isEqualTo(ScheduleOrigin.API);
        assertThat(fromGit.get(0).origin()).isEqualTo(ScheduleOrigin.CRON_FILE);
        // Same id, same content: same business key and same clock, so the hand-over neither skips nor repeats.
        assertThat(fromGit.get(0).businessKey(at)).isEqualTo(fromUi.get(0).businessKey(at))
                .isEqualTo("report-cron-nightly-2026-10-14T09:00:00Z");
        assertThat(fromGit.get(0).trackingKey()).isEqualTo(fromUi.get(0).trackingKey());
        assertThat(fromUi.get(0).manualBusinessKey(at.toInstant().plusMillis(12)))
                .isEqualTo("report-cron-manual-nightly-2026-10-14T09:00:00.012Z");
    }

    @Test
    void aFileDeclaringAnIdReplacesTheScheduleCreatedThroughTheEngine() {
        var catalog = ScheduleCatalog.of(List.of(definition("report", null)), List.of(
                api("nightly", "report", "0 0 9 * * *"),
                api("weekly", "report", "0 0 9 * * MON"),
                new CronFileSchedule("/srv/defs", 0, "report", new CronSchedule("nightly", "0 0 21 * * *", null, null))));

        assertThat(catalog).extracting(Schedule::id, Schedule::origin, s -> s.schedule().cron()).containsExactly(
                org.assertj.core.groups.Tuple.tuple("nightly", ScheduleOrigin.CRON_FILE, "0 0 21 * * *"),
                org.assertj.core.groups.Tuple.tuple("weekly", ScheduleOrigin.API, "0 0 9 * * MON"));
        assertThat(ScheduleCatalog.fileOwnedIds(catalog)).containsExactly("nightly");
    }

    @Test
    void aSecondFileDeclarationOfAnIdIsADuplicateThatNeverOwnsIt() {
        var catalog = ScheduleCatalog.of(
                List.of(definition("a", null, new CronSchedule("shared", "0 0 1 * * *", null, null))),
                List.of(new CronFileSchedule("classpath:/workflows/cron.yaml", 0, "b",
                        new CronSchedule("shared", "0 0 2 * * *", null, null))));

        assertThat(catalog.get(0).duplicateOf()).isNull();
        assertThat(catalog.get(1).duplicateOf()).isEqualTo("definition (schedules[0])");
        assertThat(ScheduleCatalog.byId(catalog)).containsOnlyKeys("shared");
        assertThat(ScheduleCatalog.byId(catalog).get("shared").workflowId()).isEqualTo("a");
    }

    @Test
    void saysWhereEachScheduleComesFrom() {
        var catalog = ScheduleCatalog.of(List.of(definition("w", "@daily")), List.of(
                new CronFileSchedule("classpath:/workflows/cron.yaml", 0, "w", new CronSchedule("x1", "@daily", null, null)),
                new CronFileSchedule("https://github.com/acme/defs#flows", 0, "w", new CronSchedule("x2", "@daily", null, null)),
                new CronFileSchedule("git@github.com:acme/defs.git", 0, "w", new CronSchedule("x3", "@daily", null, null)),
                new CronFileSchedule("/srv/defs", 0, "w", new CronSchedule("x4", "@daily", null, null)),
                api("x5", "w", "@daily")));

        assertThat(catalog).extracting(Schedule::sourceLabel).containsExactly(
                // cron.yaml entries in source order
                "Definition", "cron.yaml (directory)", "cron.yaml (classpath)", "cron.yaml (git)", "cron.yaml (git)", "UI/API");
        assertThat(catalog).extracting(Schedule::editable).containsExactly(false, false, false, false, false, true);
    }
}
