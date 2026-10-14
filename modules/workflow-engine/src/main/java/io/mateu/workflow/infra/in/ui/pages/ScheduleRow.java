package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.uidl.annotations.Hidden;
import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.domain.aggregates.CronSchedule;
import io.mateu.workflow.time.Moments;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;

/**
 * One row in the "Schedules" list of a definition: a scheduled start, wherever it was declared — the
 * definition's {@code cronExpression}, an entry of its {@code schedules}, or a {@code cron.yaml} entry
 * targeting it.
 */
public record ScheduleRow(
        @Hidden String id,
        @Label("Declared in") String declaredIn,
        @Label("Cron") String cron,
        @Label("Zone") String zone,
        @Label("Variables") String variables,
        @Label("Next start") String nextFire
) implements Identifiable {

    private static final DateTimeFormatter NEXT_FIRE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx '('VV')'");

    /** A row for {@code schedule}, its zone shown as the one it is actually read in. */
    static ScheduleRow of(String id, String declaredIn, CronSchedule schedule, String effectiveZoneWhenNone, Instant now) {
        var variables = schedule.variablesOrEmpty().isEmpty() ? "—"
                : schedule.variablesOrEmpty().entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .collect(Collectors.joining(", "));
        return new ScheduleRow(id, declaredIn, schedule.cron(),
                schedule.zone() != null ? schedule.zone() : effectiveZoneWhenNone + " (default)",
                variables,
                schedule.nextFire(now).map(NEXT_FIRE::format).orElse("—"));
    }

    /** The zone a schedule without one is read in. */
    static String defaultZone() {
        return Moments.defaultZone().getId();
    }
}
