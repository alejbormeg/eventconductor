package io.mateu.workflow.domain.aggregates;

/**
 * Where a scheduled start is declared, which decides who owns it.
 *
 * <ul>
 *   <li>{@link #DEFINITION} — the definition's {@code cronExpression} or an entry of its
 *       {@code schedules}: owned by the definition file.</li>
 *   <li>{@link #CRON_FILE} — an entry of a {@code cron.yaml} imported from the classpath, a
 *       directory or a git repository: owned by that file.</li>
 *   <li>{@link #API} — created from the admin UI, the REST API or an MCP tool, and kept in the engine's
 *       store until a file declares the same id.</li>
 * </ul>
 *
 * <p>Only {@link #API} schedules are edited or deleted through the engine. A file-owned schedule is
 * changed by changing its file; through the engine it can only be paused, resumed or run now.
 */
public enum ScheduleOrigin {
    DEFINITION,
    CRON_FILE,
    API
}
