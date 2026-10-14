package io.mateu.workflow.infra.out.memory;

import io.mateu.workflow.application.out.CronScheduleRepository;
import io.mateu.workflow.domain.aggregates.CronFileSchedule;
import io.mateu.workflow.domain.aggregates.Schedule;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code cron.yaml} and engine-created schedules for memory persistence: one JVM, so a map by source is
 * the whole store.
 */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryCronScheduleRepository implements CronScheduleRepository {

    private final Map<String, List<CronFileSchedule>> bySource = new TreeMap<>();

    @Override
    public synchronized List<CronFileSchedule> findAll() {
        return bySource.values().stream().flatMap(List::stream).toList();
    }

    @Override
    public synchronized void replace(String source, List<CronFileSchedule> schedules) {
        if (Schedule.API_SOURCE.equals(source)) {
            throw new IllegalArgumentException("'" + source + "' holds the schedules created through the engine; no import replaces it");
        }
        if (schedules == null || schedules.isEmpty()) {
            bySource.remove(source);
        } else {
            bySource.put(source, List.copyOf(schedules));
        }
    }

    @Override
    public synchronized void save(CronFileSchedule schedule) {
        var id = schedule.schedule().id();
        var api = new ArrayList<>(bySource.getOrDefault(Schedule.API_SOURCE, List.of()));
        api.removeIf(s -> s.schedule().id().equals(id));
        api.add(schedule);
        bySource.put(Schedule.API_SOURCE, List.copyOf(api));
    }

    @Override
    public synchronized void deleteApiSchedules(Collection<String> scheduleIds) {
        var api = new ArrayList<>(bySource.getOrDefault(Schedule.API_SOURCE, List.of()));
        if (api.removeIf(s -> scheduleIds.contains(s.schedule().id()))) {
            if (api.isEmpty()) {
                bySource.remove(Schedule.API_SOURCE);
            } else {
                bySource.put(Schedule.API_SOURCE, List.copyOf(api));
            }
        }
    }
}
