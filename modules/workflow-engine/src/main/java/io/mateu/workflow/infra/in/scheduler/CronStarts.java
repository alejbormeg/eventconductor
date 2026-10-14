package io.mateu.workflow.infra.in.scheduler;

import io.mateu.workflow.application.out.CronScheduleRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.CronFileSchedule;
import io.mateu.workflow.domain.aggregates.CronSchedule;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import io.mateu.workflow.security.AuthorizationContext;
import io.mateu.workflow.time.Moments;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Single cron scan shared by both scheduler variants (memory and JPA). For every ACTIVE definition,
 * publishes one {@link ProcessCreationRequested} per occurrence, elapsed since the last scan, of each
 * of its scheduled starts:
 *
 * <ul>
 *   <li>its {@code cronExpression} — the legacy shorthand: no variables, read in the JVM's zone,
 *       business key {@code <defId>-cron-<local occurrence>}, exactly as before schedules existed;</li>
 *   <li>each entry {@code i} of its {@code schedules} — business key
 *       {@code <defId>-cron-s<i>-<occurrence with offset>};</li>
 *   <li>each {@code cron.yaml} entry targeting it — business key
 *       {@code <defId>-cron-f<hash>-<occurrence with offset>}, the hash being of the entry's cron, zone
 *       and variables, so the same entry declared in two sources still starts one process.</li>
 * </ul>
 *
 * <p>The business key is derived deterministically from the schedule and the occurrence, so
 * redeliveries and concurrent pods (whose in-memory last-fire clocks differ) collapse into a single
 * process instance through the creation idempotency guard. A schedule without a zone of its own is
 * read in {@code workflow.time.zone} ({@link Moments#defaultZone()}).
 *
 * <p>Occurrences are tracked per schedule from scheduler start: schedules missed while no node was
 * running are skipped, not replayed. The tracking key includes the schedule's content, so editing a
 * schedule starts it afresh instead of replaying the new expression since the old one last fired.
 */
@Slf4j
class CronStarts {

    static final DateTimeFormatter BUSINESS_KEY_TIMESTAMP = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    /** Seconds always, and the offset, so a DST fall-back hour cannot produce one key twice. */
    static final DateTimeFormatter ZONED_BUSINESS_KEY_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    /** One scheduled start, resolved for a scan. */
    record ScheduledStart(String trackingKey, String definitionId, CronSchedule schedule, ZoneId zone,
                          String businessKeyPrefix, boolean legacy) {

        String businessKey(ZonedDateTime occurrence) {
            return businessKeyPrefix + (legacy
                    ? occurrence.toLocalDateTime().format(BUSINESS_KEY_TIMESTAMP)
                    : occurrence.format(ZONED_BUSINESS_KEY_TIMESTAMP));
        }

        List<Variable> variables() {
            return schedule.variablesOrEmpty().entrySet().stream()
                    .map(e -> new Variable(e.getKey(), e.getValue()))
                    .toList();
        }

        /** The next occurrence strictly after {@code after}, or empty when the expression has none. */
        Optional<ZonedDateTime> next(ZonedDateTime after) {
            return Optional.ofNullable(CronExpression.parse(schedule.cron()).next(after.withZoneSameInstant(zone)));
        }
    }

    static void fireDue(WorkflowDefinitionRepository workflowDefinitionRepository,
                        CronScheduleRepository cronScheduleRepository,
                        Consumer<ProcessCreationRequested> sink,
                        Map<String, ZonedDateTime> lastFireTimes,
                        Instant now) {
        fireDue(workflowDefinitionRepository.findAll(),
                cronScheduleRepository == null ? List.of() : cronScheduleRepository.findAll(),
                sink, lastFireTimes, now);
    }

    static void fireDue(Collection<WorkflowDefinition> definitions,
                        Collection<CronFileSchedule> fileSchedules,
                        Consumer<ProcessCreationRequested> sink,
                        Map<String, ZonedDateTime> lastFireTimes,
                        Instant now) {
        var seen = new HashSet<String>();
        for (var start : scheduledStarts(definitions, fileSchedules)) {
            if (!seen.add(start.trackingKey())) {
                continue;   // the same cron.yaml entry declared twice: one start is enough
            }
            fire(start, sink, lastFireTimes, now);
        }
        // A schedule that is gone (edited, removed, its definition disabled) stops being tracked.
        lastFireTimes.keySet().retainAll(seen);
    }

    private static void fire(ScheduledStart start, Consumer<ProcessCreationRequested> sink,
                             Map<String, ZonedDateTime> lastFireTimes, Instant now) {
        var nowZoned = now.atZone(start.zone());
        var last = lastFireTimes.computeIfAbsent(start.trackingKey(), key -> nowZoned);
        var next = start.next(last);
        while (next.isPresent() && !next.get().toInstant().isAfter(now)) {
            var occurrence = next.get();
            var businessKey = start.businessKey(occurrence);
            log.info("Cron start for workflow definition '{}' (occurrence {}, key {})",
                    start.definitionId(), occurrence, businessKey);
            // The scheduler is the engine acting for itself, not a caller: SYSTEM says so, so a
            // definition with flow-authorization requirements still runs on its schedule.
            sink.accept(new ProcessCreationRequested(start.definitionId(), businessKey, start.variables(),
                    null, AuthorizationContext.SYSTEM));
            last = occurrence;
            next = start.next(last);
        }
        lastFireTimes.put(start.trackingKey(), last);
    }

    /**
     * Every scheduled start of every ACTIVE definition, invalid ones (unparseable cron, unknown zone)
     * logged and left out — a definition loaded without validation (memory mode) cannot break the scan.
     */
    static List<ScheduledStart> scheduledStarts(Collection<WorkflowDefinition> definitions,
                                                Collection<CronFileSchedule> fileSchedules) {
        var starts = new ArrayList<ScheduledStart>();
        var byId = new TreeMap<String, WorkflowDefinition>();
        for (var definition : definitions) {
            if (definition.id() == null || !definition.status().accceptsNewInstances()) {
                continue;
            }
            byId.put(definition.id(), definition);
            var id = definition.id();
            if (definition.cronExpression() != null && !definition.cronExpression().isBlank()) {
                var schedule = new CronSchedule(definition.cronExpression());
                resolve(id + "#legacy#" + schedule.cron(), id, schedule, ZoneId.systemDefault(),
                        id + "-cron-", true, "cronExpression").ifPresent(starts::add);
            }
            var schedules = definition.schedules() == null ? List.<CronSchedule>of() : definition.schedules();
            for (int i = 0; i < schedules.size(); i++) {
                var schedule = schedules.get(i);
                if (schedule == null) {
                    continue;
                }
                resolve(id + "#s" + i + "#" + contentHash(schedule), id, schedule, null,
                        id + "-cron-s" + i + "-", false, "schedules[" + i + "]").ifPresent(starts::add);
            }
        }
        for (var fileSchedule : fileSchedules) {
            var definition = byId.get(fileSchedule.workflowId());
            if (definition == null) {
                continue;   // unknown, disabled or archived: nothing to start
            }
            var hash = contentHash(fileSchedule.schedule());
            resolve(definition.id() + "#f" + hash, definition.id(), fileSchedule.schedule(), null,
                    definition.id() + "-cron-f" + hash + "-", false,
                    "cron file " + fileSchedule.source() + " entry " + fileSchedule.index())
                    .ifPresent(starts::add);
        }
        return starts;
    }

    private static Optional<ScheduledStart> resolve(String trackingKey, String definitionId, CronSchedule schedule,
                                                    ZoneId fixedZone, String businessKeyPrefix, boolean legacy,
                                                    String where) {
        if (schedule.cron() == null || !CronExpression.isValidExpression(schedule.cron())) {
            log.error("Invalid cron expression '{}' ({}) on workflow definition '{}', skipping",
                    schedule.cron(), where, definitionId);
            return Optional.empty();
        }
        ZoneId zone = fixedZone;
        if (zone == null) {
            try {
                zone = schedule.zone() == null ? Moments.defaultZone() : ZoneId.of(schedule.zone());
            } catch (DateTimeException e) {
                log.error("Invalid zone '{}' ({}) on workflow definition '{}', skipping",
                        schedule.zone(), where, definitionId);
                return Optional.empty();
            }
        }
        return Optional.of(new ScheduledStart(trackingKey, definitionId, schedule, zone, businessKeyPrefix, legacy));
    }

    /** First 8 hex chars of a SHA-256 over the schedule's cron, zone and variables (in name order). */
    static String contentHash(CronSchedule schedule) {
        var text = new StringBuilder()
                .append(schedule.cron()).append('\n')
                .append(schedule.zone() == null ? "" : schedule.zone()).append('\n');
        new TreeMap<>(schedule.variablesOrEmpty()).forEach((k, v) -> text.append(k).append('=').append(v).append('\n'));
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private CronStarts() {
    }
}
