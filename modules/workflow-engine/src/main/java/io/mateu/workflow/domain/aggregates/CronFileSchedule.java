package io.mateu.workflow.domain.aggregates;

/**
 * A scheduled start read from a {@code cron.yaml} file, as the engine keeps it: which workflow it
 * starts, the schedule itself, and where it came from.
 *
 * @param source     the source the file was imported from — {@code classpath:/workflows/cron.yaml},
 *                   an imported directory's absolute path, a git repository url (the same key the
 *                   definition import prunes by). Re-importing a source replaces all its entries.
 * @param index      the entry's position in its source, for listing in the order it was written
 * @param workflowId the id of the workflow definition each occurrence starts
 * @param schedule   the cron, zone and initial variables
 */
public record CronFileSchedule(String source, int index, String workflowId, CronSchedule schedule) {
}
