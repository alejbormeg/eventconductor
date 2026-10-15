package io.mateu.workflow.application.out;

import io.mateu.workflow.domain.aggregates.TriggerPause;

import java.util.Map;

/** Paused event triggers, by {@code <workflow>/<trigger>}. */
public interface TriggerPauseRepository {

    Map<String, TriggerPause> findAll();

    /** Pauses; an existing pause is kept as it was (when and by whom). */
    void save(TriggerPause pause);

    void delete(String triggerKey);
}
