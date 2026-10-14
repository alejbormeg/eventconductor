-- Schedules with ids, created and paused through the engine.
--
-- A schedule may declare an id (cron.yaml and a definition's `schedules` entries) and always has one
-- when it was created from the admin UI, the REST API or an MCP tool. Those are stored in
-- cron_schedule too, under source_key 'ui'; schedule_id is the declared id (null for a cron.yaml
-- entry that declares none). An import replaces its own source only — never 'ui' — and deletes the
-- 'ui' rows whose id a file now declares: the file's entry has taken that schedule over.
--
-- Pauses are kept apart, by schedule id, because every import rewrites the rows of its source: a flag
-- on the row would not survive the next import of a file-owned schedule.
--
-- Plain portable DDL with IF NOT EXISTS, as V29-V37: it also runs over a schema ddl-auto created
-- from the entities (CronScheduleEntity, SchedulePauseEntity).
ALTER TABLE cron_schedule ADD COLUMN IF NOT EXISTS schedule_id VARCHAR(160);

CREATE INDEX IF NOT EXISTS idx_cron_schedule_schedule_id ON cron_schedule (schedule_id);

CREATE TABLE IF NOT EXISTS cron_schedule_pause (
    schedule_id  VARCHAR(160) NOT NULL PRIMARY KEY,
    paused_at    TIMESTAMP    NOT NULL,
    paused_by    VARCHAR(255)
);
