package io.mateu.workflow.domain.aggregates;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One scheduled start: at every occurrence of {@code cron}, read in {@code zone}, the engine creates
 * a process instance whose initial variables are {@code variables}.
 *
 * <p>Carried in a definition's {@code schedules} list, and — with the workflow it targets — in a
 * {@code cron.yaml} file next to the definitions (see {@code CronFileSchedule}).
 *
 * @param cron      a Spring cron expression: six fields, seconds first ({@code "0 0 9 * * MON-FRI"})
 * @param zone      the IANA zone the expression is read in; null means the engine's
 *                  {@code workflow.time.zone} (the JVM's zone unless configured)
 * @param variables the process variables each start is created with, by name; null or empty means none
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CronSchedule(String cron, String zone, Map<String, String> variables) {

    public CronSchedule {
        cron = cron == null ? null : cron.trim();
        zone = zone == null || zone.isBlank() ? null : zone.trim();
        // Insertion order kept: it is the order the author wrote the variables in, and the order the
        // detail view lists them in. Null stays null so the exporter does not write `variables: {}`.
        variables = variables == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
    }

    /** A schedule with no zone of its own and no variables. */
    public CronSchedule(String cron) {
        this(cron, null, null);
    }

    /**
     * The first occurrence after {@code now}, in the schedule's zone ({@code workflow.time.zone} when it
     * names none); empty when the expression never fires again or the schedule is invalid. For display:
     * the scheduler computes its own occurrences from when it last fired.
     */
    public java.util.Optional<java.time.ZonedDateTime> nextFire(java.time.Instant now) {
        try {
            var zoneId = zone == null ? io.mateu.workflow.time.Moments.defaultZone() : java.time.ZoneId.of(zone);
            return java.util.Optional.ofNullable(
                    org.springframework.scheduling.support.CronExpression.parse(cron).next(now.atZone(zoneId)));
        } catch (RuntimeException e) {
            return java.util.Optional.empty();
        }
    }

    /** The variables, never null. */
    public Map<String, String> variablesOrEmpty() {
        return variables == null ? Map.of() : variables;
    }
}
