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
 * One paused event trigger, by {@code <workflow>/<trigger>} (see {@code TriggerPauseRepository}).
 * Declared here as well as in {@code V39__domain_event_triggers.sql}, for the schemas {@code ddl-auto}
 * creates.
 */
@Entity
@Table(name = "event_trigger_pause")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class EventTriggerPauseEntity {

    @Id
    @Column(name = "trigger_key", length = 420)
    private String triggerKey;

    /** In UTC. */
    @Column(name = "paused_at", nullable = false)
    private LocalDateTime pausedAt;

    @Column(name = "paused_by")
    private String pausedBy;
}
