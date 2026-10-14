package io.mateu.workflow.domain.aggregates;

import io.mateu.workflow.time.Moments;
import org.springframework.scheduling.support.CronExpression;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/**
 * One scheduled start as the engine knows it, wherever it is declared — with the identity the
 * scheduler fires it under, pauses it under and the UI, REST API and MCP tools address it by.
 *
 * <p><b>Identity.</b> One schedule per id. A schedule declares its id ({@code id:} in a definition's
 * {@code schedules} or a {@code cron.yaml} entry; always, for one created through the engine), or the
 * engine derives one from what it is, so that it is the same on every import:
 *
 * <ul>
 *   <li>{@code <workflow>~cron} for a definition's {@code cronExpression};</li>
 *   <li>{@code <workflow>~s<hash>} for an entry of its {@code schedules}, {@code ~2}, {@code ~3}…
 *       appended to a second identical entry;</li>
 *   <li>{@code <workflow>~f<hash>} for a {@code cron.yaml} entry,</li>
 * </ul>
 *
 * <p>the hash being of the cron, zone and variables. {@code ~} is not allowed in a declared id, so
 * the two never meet. A derived id follows the content: edit the entry and it is another schedule
 * (a pause on it does not carry over) — declare an id for a schedule whose pause must survive edits.
 *
 * <p><b>Business keys.</b> A schedule with a declared id starts {@code <workflow>-cron-<id>-<occurrence
 * with offset>}, whoever owns it: when a {@code cron.yaml} entry takes over a schedule created from
 * the UI, the same occurrence still has the same key, and the creation idempotency guard collapses
 * any overlap into one process. The others keep the keys they always had.
 *
 * @param id         the schedule's id, declared or derived
 * @param idDeclared whether {@code id} was declared rather than derived
 * @param origin     where it is declared, which decides who owns it
 * @param source     the definition id for {@link ScheduleOrigin#DEFINITION}, the import source key for
 *                   {@link ScheduleOrigin#CRON_FILE}, {@link #API_SOURCE} for {@link ScheduleOrigin#API}
 * @param position   its index in the definition's {@code schedules} or in its source (-1 for
 *                   {@code cronExpression})
 * @param workflowId the workflow each occurrence starts
 * @param schedule   cron, zone and variables
 * @param legacy     whether it is a definition's {@code cronExpression} — read in the JVM's zone, with
 *                   its historical business key
 * @param duplicateOf null, or where the declaration that holds this id is: a second declaration of an
 *                   id never fires
 */
public record Schedule(String id, boolean idDeclared, ScheduleOrigin origin, String source, int position,
                       String workflowId, CronSchedule schedule, boolean legacy, String duplicateOf) {

    /** The source key schedules created through the engine (UI, REST API, MCP) are stored under. */
    public static final String API_SOURCE = "ui";

    /** Seconds always, and the offset, so a DST fall-back hour cannot produce one key twice. */
    public static final DateTimeFormatter ZONED_BUSINESS_KEY_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    static final DateTimeFormatter LEGACY_BUSINESS_KEY_TIMESTAMP = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final DateTimeFormatter MANUAL_BUSINESS_KEY_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX");

    /** Whether the engine may edit or delete it: only what was created through the engine. */
    public boolean editable() {
        return origin == ScheduleOrigin.API;
    }

    /** Whether it is owned by a file (a definition or a {@code cron.yaml}) rather than by the engine. */
    public boolean fileOwned() {
        return origin != ScheduleOrigin.API;
    }

    /** Where it comes from, for people: "Definition", "cron.yaml (git)", "UI/API"… */
    public String sourceLabel() {
        return switch (origin) {
            case DEFINITION -> "Definition";
            case CRON_FILE -> "cron.yaml (" + cronFileSourceKind(source) + ")";
            case API -> "UI/API";
        };
    }

    /** Exactly where it is declared: "definition (schedules[2])", "cron.yaml (git) https://…", "UI/API". */
    public String declaredIn() {
        return switch (origin) {
            case DEFINITION -> legacy ? "definition (cronExpression)" : "definition (schedules[" + position + "])";
            case CRON_FILE -> sourceLabel() + " " + source;
            case API -> "UI/API";
        };
    }

    /** classpath, git or directory, read off an import source key. */
    public static String cronFileSourceKind(String source) {
        if (source == null) {
            return "file";
        }
        if (source.startsWith("classpath:")) {
            return "classpath";
        }
        if (source.matches("^[A-Za-z][A-Za-z0-9+.-]*://.*") || source.startsWith("git@") || source.contains(".git")) {
            return "git";
        }
        return "directory";
    }

    /**
     * The zone its cron is read in: a {@code cronExpression} in the JVM's, as it always has been;
     * the others in their own, or {@code workflow.time.zone}. Empty when the zone is not one.
     */
    public Optional<ZoneId> effectiveZone() {
        if (legacy) {
            return Optional.of(ZoneId.systemDefault());
        }
        try {
            return Optional.of(schedule.zone() == null ? Moments.defaultZone() : ZoneId.of(schedule.zone()));
        } catch (DateTimeException e) {
            return Optional.empty();
        }
    }

    /** Whether its cron and zone can be fired at all. */
    public boolean valid() {
        return schedule.cron() != null && CronExpression.isValidExpression(schedule.cron()) && effectiveZone().isPresent();
    }

    /** The next {@code count} occurrences after {@code now}; empty when invalid or never again. */
    public List<ZonedDateTime> nextFires(Instant now, int count) {
        var fires = new ArrayList<ZonedDateTime>();
        if (!valid()) {
            return fires;
        }
        var cron = CronExpression.parse(schedule.cron());
        var at = now.atZone(effectiveZone().orElseThrow());
        for (int i = 0; i < count; i++) {
            at = cron.next(at);
            if (at == null) {
                break;
            }
            fires.add(at);
        }
        return fires;
    }

    /** What every business key of its occurrences starts with. */
    public String businessKeyPrefix() {
        if (idDeclared) {
            return workflowId + "-cron-" + id + "-";
        }
        if (legacy) {
            return workflowId + "-cron-";
        }
        return origin == ScheduleOrigin.DEFINITION
                ? workflowId + "-cron-s" + position + "-"
                : workflowId + "-cron-f" + contentHash(schedule) + "-";
    }

    /** The business key of the process started for {@code occurrence}. */
    public String businessKey(ZonedDateTime occurrence) {
        return businessKeyPrefix() + (legacy && !idDeclared
                ? occurrence.toLocalDateTime().format(LEGACY_BUSINESS_KEY_TIMESTAMP)
                : occurrence.format(ZONED_BUSINESS_KEY_TIMESTAMP));
    }

    /**
     * The business key of a start asked for by hand ("run now"): never one an occurrence has, and
     * distinct per millisecond, so a second click starts a second process.
     */
    public String manualBusinessKey(Instant at) {
        return workflowId + "-cron-manual-" + id + "-" + at.atOffset(ZoneOffset.UTC).format(MANUAL_BUSINESS_KEY_TIMESTAMP);
    }

    /**
     * What the scheduler tracks its last fire under. It includes the content, so editing a schedule
     * starts it afresh instead of replaying the new expression since the old one last fired; for a
     * declared id it does not include the owner, so a {@code cron.yaml} entry that takes over a
     * schedule created from the UI, unchanged, carries on from where it was.
     */
    public String trackingKey() {
        if (idDeclared) {
            return "id#" + id + "#" + workflowId + "#" + contentHash(schedule);
        }
        if (legacy) {
            return workflowId + "#legacy#" + schedule.cron();
        }
        return origin == ScheduleOrigin.DEFINITION
                ? workflowId + "#s" + position + "#" + contentHash(schedule)
                : workflowId + "#f" + contentHash(schedule);
    }

    /** First 8 hex chars of a SHA-256 over the schedule's cron, zone and variables (in name order). */
    public static String contentHash(CronSchedule schedule) {
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
}
