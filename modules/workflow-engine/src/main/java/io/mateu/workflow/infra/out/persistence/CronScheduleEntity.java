package io.mateu.workflow.infra.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One {@code cron.yaml} entry, or one schedule created through the engine (source {@code ui}) — see
 * {@code CronScheduleRepository}. Declared here as well as in {@code V37__cron_schedules.sql} and
 * {@code V38__schedule_ids_and_pauses.sql}, for the schemas {@code ddl-auto} creates.
 */
@Entity
@Table(name = "cron_schedule")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CronScheduleEntity {

    /** Hash of source and index: the source can be a long path or url, too long for a key. */
    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "source_key", length = 1024, nullable = false)
    private String sourceKey;

    @Column(name = "entry_index", nullable = false)
    private int entryIndex;

    @Column(name = "workflow_id", nullable = false)
    private String workflowId;

    @Column(name = "cron_expression", nullable = false)
    private String cronExpression;

    @Column(name = "zone_id", length = 64)
    private String zoneId;

    @Column(name = "variables_json", columnDefinition = "TEXT")
    private String variablesJson;

    /** The schedule's declared id; null for a {@code cron.yaml} entry that declares none. */
    @Column(name = "schedule_id", length = 160)
    private String scheduleId;
}
