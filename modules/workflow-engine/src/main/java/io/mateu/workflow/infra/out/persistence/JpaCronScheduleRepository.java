package io.mateu.workflow.infra.out.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.application.out.CronScheduleRepository;
import io.mateu.workflow.domain.aggregates.CronFileSchedule;
import io.mateu.workflow.domain.aggregates.CronSchedule;
import io.mateu.workflow.domain.aggregates.Schedule;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code cron.yaml} and engine-created schedules in the database, so every pod's cron scan reads the
 * same set.
 */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
@RequiredArgsConstructor
public class JpaCronScheduleRepository implements CronScheduleRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    final CronScheduleEntityRepository repository;

    @Override
    @Transactional(readOnly = true)
    public List<CronFileSchedule> findAll() {
        return repository.findAll().stream()
                .sorted(Comparator.comparing(CronScheduleEntity::getSourceKey)
                        .thenComparingInt(CronScheduleEntity::getEntryIndex))
                .map(JpaCronScheduleRepository::map)
                .toList();
    }

    /** Delete and insert in one transaction: a scan never sees a source half replaced. */
    @Override
    @Transactional
    public void replace(String source, List<CronFileSchedule> schedules) {
        if (Schedule.API_SOURCE.equals(source)) {
            throw new IllegalArgumentException("'" + source + "' holds the schedules created through the engine; no import replaces it");
        }
        repository.deleteBySourceKey(source);
        repository.flush();
        if (schedules == null || schedules.isEmpty()) {
            return;
        }
        repository.saveAll(schedules.stream().map(s -> new CronScheduleEntity(
                id(source, s.index()), source, s.index(), s.workflowId(), s.schedule().cron(),
                s.schedule().zone(), variablesToJson(s.schedule().variables()), s.schedule().id())).toList());
    }

    /** Keyed by the schedule's id, so saving it again (an edit) overwrites the row. */
    @Override
    @Transactional
    public void save(CronFileSchedule schedule) {
        var id = schedule.schedule().id();
        repository.save(new CronScheduleEntity(id(Schedule.API_SOURCE, id), Schedule.API_SOURCE, schedule.index(),
                schedule.workflowId(), schedule.schedule().cron(), schedule.schedule().zone(),
                variablesToJson(schedule.schedule().variables()), id));
    }

    @Override
    @Transactional
    public void deleteApiSchedules(Collection<String> scheduleIds) {
        if (scheduleIds == null || scheduleIds.isEmpty()) {
            return;
        }
        repository.deleteBySourceKeyAndScheduleIdIn(Schedule.API_SOURCE, scheduleIds);
    }

    private static CronFileSchedule map(CronScheduleEntity row) {
        return new CronFileSchedule(row.getSourceKey(), row.getEntryIndex(), row.getWorkflowId(),
                new CronSchedule(row.getScheduleId(), row.getCronExpression(), row.getZoneId(),
                        variablesFromJson(row.getVariablesJson())));
    }

    static String id(String source, int index) {
        return id(source, String.valueOf(index));
    }

    /** A {@code cron.yaml} row is keyed by its position in its source, an engine-created one by its id. */
    static String id(String source, String key) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest((source + "#" + key).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String variablesToJson(Map<String, String> variables) {
        if (variables == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(variables);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise schedule variables", e);
        }
    }

    private static Map<String, String> variablesFromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, String>>() {});
        } catch (Exception e) {
            return null;
        }
    }
}
