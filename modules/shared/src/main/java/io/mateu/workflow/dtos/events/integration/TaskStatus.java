package io.mateu.workflow.dtos.events.integration;

public enum TaskStatus {
    CREATED,
    PENDING,
    RUNNING,
    REVIEW_REQUIRED,
    COMPLETED,
    CANCELLED,
    ERROR,
    TIMEOUT
}
