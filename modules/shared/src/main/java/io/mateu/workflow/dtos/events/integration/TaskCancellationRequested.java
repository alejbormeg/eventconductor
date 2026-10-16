package io.mateu.workflow.dtos.events.integration;

import io.mateu.workflow.ddd.DomainEvent;

public record TaskCancellationRequested(String taskId,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        String invocationId) implements DomainEvent {
    public TaskCancellationRequested(String taskId) { this(taskId, null); }
}
