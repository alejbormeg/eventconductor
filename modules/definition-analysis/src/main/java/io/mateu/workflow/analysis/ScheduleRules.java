package io.mateu.workflow.analysis;

import org.springframework.scheduling.support.CronExpression;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The rules of a scheduled start — a definition's {@code cronExpression}, an entry of its
 * {@code schedules}, or an entry of a {@code cron.yaml} file — shared as code by the engine and the
 * Maven plugin, so a schedule the build accepts is one the engine fires and vice versa.
 *
 * <ul>
 *   <li>{@code cron} is a Spring {@link CronExpression}: six fields, seconds first
 *       ({@code "0 0 9 * * MON-FRI"}), macros such as {@code @daily} included.</li>
 *   <li>{@code zone}, when present, is an IANA zone id ({@code Europe/Madrid}) or anything else
 *       {@link ZoneId#of(String)} reads ({@code UTC}, {@code +02:00}).</li>
 *   <li>{@code id}, optional, names the schedule: letters, digits, {@code .}, {@code _} and
 *       {@code -}, starting with a letter or digit, at most {@value #MAX_ID_LENGTH} characters
 *       ({@code nightly-report}). One schedule per id across the engine — the id is what a schedule
 *       created from the UI is matched by when it is later written into a {@code cron.yaml}, and
 *       what a pause is kept under.</li>
 * </ul>
 */
public final class ScheduleRules {

    /**
     * The file names a schedule file is written with. A file of one of these names next to the
     * workflow definitions holds schedules, never a definition, and every importer must skip it as one.
     */
    public static final Set<String> CRON_FILE_NAMES = Set.of("cron.yaml", "cron.yml");

    /** The longest schedule id accepted. */
    public static final int MAX_ID_LENGTH = 100;

    private static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private ScheduleRules() {
    }

    /** Whether this is a schedule id; null or blank means "none declared" and is fine. */
    public static boolean isValidId(String id) {
        if (id == null || id.isBlank()) {
            return true;
        }
        var trimmed = id.trim();
        return trimmed.length() <= MAX_ID_LENGTH && ID.matcher(trimmed).matches();
    }

    /** Whether a file name (or path; only the last segment counts) is a schedule file. */
    public static boolean isCronFileName(String name) {
        if (name == null) {
            return false;
        }
        var normalized = name.replace('\\', '/');
        var slash = normalized.lastIndexOf('/');
        var last = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return CRON_FILE_NAMES.contains(last.toLowerCase(Locale.ROOT));
    }

    /** Whether Spring's cron parser accepts this expression (blank is not a cron expression). */
    public static boolean isValidCron(String cron) {
        return cron != null && !cron.isBlank() && CronExpression.isValidExpression(cron.trim());
    }

    /** Whether this is a zone the engine can read; null or blank means "the default" and is fine. */
    public static boolean isValidZone(String zone) {
        if (zone == null || zone.isBlank()) {
            return true;
        }
        try {
            ZoneId.of(zone.trim());
            return true;
        } catch (DateTimeException e) {
            return false;
        }
    }

    /**
     * What is wrong with one schedule, each message prefixed with {@code where} (for instance
     * {@code "schedules[0]"}); empty means it is valid.
     */
    public static List<String> violations(String cron, String zone, String where) {
        return violations(null, cron, zone, where);
    }

    /** As {@link #violations(String, String, String)}, checking the schedule's optional id too. */
    public static List<String> violations(String id, String cron, String zone, String where) {
        var violations = new ArrayList<String>();
        var prefix = where == null || where.isBlank() ? "" : where + ": ";
        if (!isValidId(id)) {
            violations.add(prefix + "'" + id + "' is not a valid schedule id (letters, digits, '.', '_' and '-',"
                    + " starting with a letter or digit, at most " + MAX_ID_LENGTH + " characters)");
        }
        if (cron == null || cron.isBlank()) {
            violations.add(prefix + "a schedule needs a cron expression");
        } else if (!isValidCron(cron)) {
            violations.add(prefix + "'" + cron + "' is not a valid cron expression"
                    + " (six fields, seconds first, e.g. \"0 0 9 * * MON-FRI\")");
        }
        if (!isValidZone(zone)) {
            violations.add(prefix + "'" + zone + "' is not a valid time zone (an IANA id such as Europe/Madrid)");
        }
        return violations;
    }

    /**
     * The ids declared more than once in one list of schedules (a definition's {@code schedules}, a
     * {@code cron.yaml}), each reported once with {@code where} — an id names one schedule.
     */
    public static List<String> duplicateIdViolations(List<String> ids, String where) {
        var seen = new java.util.HashSet<String>();
        var reported = new java.util.LinkedHashSet<String>();
        for (var id : ids) {
            if (id != null && !id.isBlank() && !seen.add(id.trim())) {
                reported.add(id.trim());
            }
        }
        var prefix = where == null || where.isBlank() ? "" : where + ": ";
        return reported.stream().map(id -> prefix + "schedule id '" + id + "' is declared more than once").toList();
    }
}
