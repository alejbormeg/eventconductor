package io.mateu.workflow.infra.out.memory;

import io.mateu.workflow.application.out.TriggerPauseRepository;
import io.mateu.workflow.domain.aggregates.TriggerPause;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Trigger pauses for memory persistence. */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryTriggerPauseRepository implements TriggerPauseRepository {

    private final Map<String, TriggerPause> pauses = new ConcurrentHashMap<>();

    @Override
    public Map<String, TriggerPause> findAll() {
        return new LinkedHashMap<>(pauses);
    }

    @Override
    public void save(TriggerPause pause) {
        pauses.putIfAbsent(pause.triggerKey(), pause);
    }

    @Override
    public void delete(String triggerKey) {
        pauses.remove(triggerKey);
    }
}
