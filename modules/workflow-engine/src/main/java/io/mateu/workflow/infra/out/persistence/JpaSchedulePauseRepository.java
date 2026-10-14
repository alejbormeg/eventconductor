package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.SchedulePauseRepository;
import io.mateu.workflow.domain.aggregates.SchedulePause;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Schedule pauses in the database, so the pod holding the cron lock sees a pause made on another. */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
@RequiredArgsConstructor
public class JpaSchedulePauseRepository implements SchedulePauseRepository {

    final SchedulePauseEntityRepository repository;

    @Override
    @Transactional(readOnly = true)
    public Map<String, SchedulePause> findAll() {
        var pauses = new LinkedHashMap<String, SchedulePause>();
        repository.findAll().forEach(row -> pauses.put(row.getScheduleId(),
                new SchedulePause(row.getScheduleId(), row.getPausedAt().toInstant(ZoneOffset.UTC), row.getPausedBy())));
        return pauses;
    }

    @Override
    @Transactional
    public void save(SchedulePause pause) {
        if (!repository.existsById(pause.scheduleId())) {
            repository.save(new SchedulePauseEntity(pause.scheduleId(),
                    LocalDateTime.ofInstant(pause.pausedAt(), ZoneOffset.UTC), pause.pausedBy()));
        }
    }

    @Override
    @Transactional
    public void deleteAll(Collection<String> scheduleIds) {
        if (scheduleIds != null && !scheduleIds.isEmpty()) {
            repository.deleteAllById(scheduleIds);
        }
    }
}
