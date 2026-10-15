package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.TriggerPauseRepository;
import io.mateu.workflow.domain.aggregates.TriggerPause;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/** Trigger pauses in the database, so the pod consuming an event sees a pause made on another. */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
@RequiredArgsConstructor
public class JpaTriggerPauseRepository implements TriggerPauseRepository {

    final EventTriggerPauseEntityRepository repository;

    @Override
    @Transactional(readOnly = true)
    public Map<String, TriggerPause> findAll() {
        var pauses = new LinkedHashMap<String, TriggerPause>();
        repository.findAll().forEach(row -> pauses.put(row.getTriggerKey(),
                new TriggerPause(row.getTriggerKey(), row.getPausedAt().toInstant(ZoneOffset.UTC), row.getPausedBy())));
        return pauses;
    }

    @Override
    @Transactional
    public void save(TriggerPause pause) {
        if (!repository.existsById(pause.triggerKey())) {
            repository.save(new EventTriggerPauseEntity(pause.triggerKey(),
                    LocalDateTime.ofInstant(pause.pausedAt(), ZoneOffset.UTC), pause.pausedBy()));
        }
    }

    @Override
    @Transactional
    public void delete(String triggerKey) {
        repository.deleteById(triggerKey);
    }
}
