package io.mateu.workflow.application.out;

import io.mateu.workflow.domain.aggregates.CronFileSchedule;

import java.util.List;

/**
 * The schedules read from {@code cron.yaml} files, grouped by the source each file came from.
 *
 * <p>Kept where every pod's cron scan can read them: in memory for {@code workflow.persistence=memory}
 * (one JVM), in the database for {@code jpa} — so a re-import that lands on one pod (a git webhook)
 * changes what the pod holding the cron lock fires.
 */
public interface CronScheduleRepository {

    /** Every schedule from every source, in source then entry order. */
    List<CronFileSchedule> findAll();

    /** The schedules targeting one workflow definition. */
    default List<CronFileSchedule> findByWorkflowId(String workflowId) {
        return findAll().stream().filter(s -> s.workflowId().equals(workflowId)).toList();
    }

    /**
     * Replaces everything recorded for {@code source} with {@code schedules} (each of which must carry
     * that source): an entry no longer in the file stops firing, an empty list clears the source.
     */
    void replace(String source, List<CronFileSchedule> schedules);
}
