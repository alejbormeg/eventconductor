package io.mateu.workflow.infra.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One paused schedule, by schedule id (see {@code SchedulePauseRepository}). Declared here as well as
 * in {@code V38__schedule_ids_and_pauses.sql}, for the schemas {@code ddl-auto} creates.
 */
@Entity
@Table(name = "cron_schedule_pause")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SchedulePauseEntity {

    @Id
    @Column(name = "schedule_id", length = 160)
    private String scheduleId;

    /** In UTC, as a plain TIMESTAMP like the engine's other columns. */
    @Column(name = "paused_at", nullable = false)
    private LocalDateTime pausedAt;

    @Column(name = "paused_by")
    private String pausedBy;
}
