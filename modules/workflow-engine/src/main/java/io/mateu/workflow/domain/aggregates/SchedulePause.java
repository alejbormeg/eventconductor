package io.mateu.workflow.domain.aggregates;

import java.time.Instant;

/**
 * An operator's decision that a schedule must not fire, kept apart from the schedule itself.
 *
 * <p>Apart, because most schedules are owned by files and every import rewrites what a file
 * declares: a flag on the imported entry would be lost on the next import. Keyed by the schedule's id
 * instead — the declared one, or for an entry without one the id derived from its workflow and
 * content (see {@link Schedule}) — so a pause outlives re-imports, and follows a schedule created from
 * the UI when a {@code cron.yaml} entry with the same id takes it over.
 *
 * @param scheduleId the id of the paused schedule
 * @param pausedAt   when it was paused
 * @param pausedBy   who paused it, when known
 */
public record SchedulePause(String scheduleId, Instant pausedAt, String pausedBy) {
}
