package io.mateu.workflow.domain.aggregates;

import java.time.Instant;

/**
 * A paused event trigger, kept by {@code <workflow>/<trigger>} apart from the definition (whose row every
 * import rewrites), so a pause survives re-imports — the schedule-pause rule.
 */
public record TriggerPause(String triggerKey, Instant pausedAt, String pausedBy) {

    /** The key a trigger's pause is kept under. */
    public static String keyOf(String workflowId, String triggerId) {
        return workflowId + "/" + triggerId;
    }
}
