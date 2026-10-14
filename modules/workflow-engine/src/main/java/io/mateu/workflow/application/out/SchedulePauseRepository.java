package io.mateu.workflow.application.out;

import io.mateu.workflow.domain.aggregates.SchedulePause;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * The schedules an operator paused, by schedule id (see {@link SchedulePause} for why apart from the
 * schedules). In memory for {@code workflow.persistence=memory}, in {@code cron_schedule_pause} for
 * {@code jpa}, where every pod's cron scan reads it.
 */
public interface SchedulePauseRepository {

    /** Every pause, by schedule id. */
    Map<String, SchedulePause> findAll();

    default Optional<SchedulePause> findById(String scheduleId) {
        return Optional.ofNullable(findAll().get(scheduleId));
    }

    /** Records a pause; pausing a paused schedule keeps the first pause. */
    void save(SchedulePause pause);

    /** Lifts the pause on these schedules, if any. */
    void deleteAll(Collection<String> scheduleIds);
}
