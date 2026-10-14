package io.mateu.workflow.infra.out.memory;

import io.mateu.workflow.application.out.SchedulePauseRepository;
import io.mateu.workflow.domain.aggregates.SchedulePause;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Schedule pauses for memory persistence: one JVM, a map is the whole store. */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "memory", matchIfMissing = true)
public class InMemorySchedulePauseRepository implements SchedulePauseRepository {

    private final Map<String, SchedulePause> pauses = new ConcurrentHashMap<>();

    @Override
    public Map<String, SchedulePause> findAll() {
        return new LinkedHashMap<>(pauses);
    }

    @Override
    public void save(SchedulePause pause) {
        pauses.putIfAbsent(pause.scheduleId(), pause);
    }

    @Override
    public void deleteAll(Collection<String> scheduleIds) {
        scheduleIds.forEach(pauses::remove);
    }
}
