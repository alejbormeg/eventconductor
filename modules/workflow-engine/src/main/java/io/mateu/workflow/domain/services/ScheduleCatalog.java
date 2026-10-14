package io.mateu.workflow.domain.services;

import io.mateu.workflow.domain.aggregates.CronFileSchedule;
import io.mateu.workflow.domain.aggregates.CronSchedule;
import io.mateu.workflow.domain.aggregates.Schedule;
import io.mateu.workflow.domain.aggregates.ScheduleOrigin;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Every schedule the engine knows, each with its id: the one place that turns definitions and stored
 * {@code cron.yaml}/UI entries into {@link Schedule}s, so that the scheduler, the UI, the REST API
 * and the MCP tools all mean the same schedule by the same id. Pure.
 *
 * <p>Order, which is also precedence when two declarations carry one id: definitions (by id; each one's
 * {@code cronExpression} then its {@code schedules}), then {@code cron.yaml} entries (by source, then
 * entry), then the schedules created through the engine (by id). The first declaration of an id owns it;
 * a later <em>file</em> declaration is listed with {@link Schedule#duplicateOf()} set and never fires,
 * while a schedule created through the engine whose id a file declares is not listed at all: the file's
 * entry has replaced it (the import that brought it deletes the stored one; until then it is ignored).
 *
 * <p>Every definition is listed, whatever its status — whether a schedule fires also depends on its
 * workflow accepting instances, which is the scheduler's and the UI's business, not identity's.
 */
public final class ScheduleCatalog {

    private ScheduleCatalog() {
    }

    /** The schedules of {@code definitions} and of the {@code stored} entries, in precedence order. */
    public static List<Schedule> of(Collection<WorkflowDefinition> definitions, Collection<CronFileSchedule> stored) {
        var owners = new HashMap<String, Schedule>();
        var schedules = new ArrayList<Schedule>();
        var sorted = definitions.stream()
                .filter(d -> d.id() != null)
                .sorted(Comparator.comparing(WorkflowDefinition::id))
                .toList();
        for (var definition : sorted) {
            var workflowId = definition.id();
            if (definition.cronExpression() != null && !definition.cronExpression().isBlank()) {
                declare(new Schedule(workflowId + "~cron", false, ScheduleOrigin.DEFINITION, workflowId, -1, workflowId,
                        new CronSchedule(definition.cronExpression()), true, null), owners, schedules);
            }
            var own = definition.schedules() == null ? List.<CronSchedule>of() : definition.schedules();
            var derivedSeen = new HashMap<String, Integer>();
            for (int i = 0; i < own.size(); i++) {
                var schedule = own.get(i);
                if (schedule == null) {
                    continue;
                }
                var declared = schedule.id() != null;
                var id = declared ? schedule.id() : workflowId + "~s" + Schedule.contentHash(schedule);
                if (!declared) {
                    // Two identical entries in one definition have always been two starts: keep them apart.
                    int n = derivedSeen.merge(id, 1, Integer::sum);
                    id = n == 1 ? id : id + "~" + n;
                }
                declare(new Schedule(id, declared, ScheduleOrigin.DEFINITION, workflowId, i, workflowId, schedule,
                        false, null), owners, schedules);
            }
        }
        var files = stored.stream().filter(s -> !Schedule.API_SOURCE.equals(s.source()))
                .sorted(Comparator.comparing(CronFileSchedule::source).thenComparingInt(CronFileSchedule::index))
                .toList();
        for (var entry : files) {
            var declared = entry.schedule().id() != null;
            var id = declared ? entry.schedule().id() : entry.workflowId() + "~f" + Schedule.contentHash(entry.schedule());
            declare(new Schedule(id, declared, ScheduleOrigin.CRON_FILE, entry.source(), entry.index(),
                    entry.workflowId(), entry.schedule(), false, null), owners, schedules);
        }
        var api = stored.stream().filter(s -> Schedule.API_SOURCE.equals(s.source()) && s.schedule().id() != null)
                .sorted(Comparator.comparing(s -> s.schedule().id()))
                .toList();
        for (var entry : api) {
            if (owners.containsKey(entry.schedule().id())) {
                continue;   // a file declares this id now: its entry is the schedule
            }
            declare(new Schedule(entry.schedule().id(), true, ScheduleOrigin.API, Schedule.API_SOURCE, entry.index(),
                    entry.workflowId(), entry.schedule(), false, null), owners, schedules);
        }
        return schedules;
    }

    /** The ids files declare (definitions and {@code cron.yaml}): an engine-created schedule with one of them has been taken over. */
    public static Set<String> fileOwnedIds(List<Schedule> catalog) {
        return catalog.stream().filter(Schedule::fileOwned).map(Schedule::id).collect(Collectors.toSet());
    }

    /** The catalog by id, the owning declaration of each id. */
    public static Map<String, Schedule> byId(List<Schedule> catalog) {
        var byId = new LinkedHashMap<String, Schedule>();
        catalog.stream().filter(s -> s.duplicateOf() == null).forEach(s -> byId.putIfAbsent(s.id(), s));
        return byId;
    }

    private static void declare(Schedule schedule, Map<String, Schedule> owners, List<Schedule> schedules) {
        var owner = owners.putIfAbsent(schedule.id(), schedule);
        if (owner == null) {
            schedules.add(schedule);
        } else {
            schedules.add(new Schedule(schedule.id(), schedule.idDeclared(), schedule.origin(), schedule.source(),
                    schedule.position(), schedule.workflowId(), schedule.schedule(), schedule.legacy(), owner.declaredIn()));
        }
    }
}
