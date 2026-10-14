package io.mateu.workflow.infra.out.memory;

import io.mateu.workflow.application.out.CronScheduleRepository;
import io.mateu.workflow.domain.aggregates.CronFileSchedule;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** {@code cron.yaml} schedules for memory persistence: one JVM, so a map by source is the whole store. */
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
        if (schedules == null || schedules.isEmpty()) {
            bySource.remove(source);
        } else {
            bySource.put(source, List.copyOf(schedules));
        }
    }
}
