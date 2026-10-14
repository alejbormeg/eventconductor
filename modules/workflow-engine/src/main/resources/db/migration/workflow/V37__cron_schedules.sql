-- Scheduled starts with parameters.
--
-- A definition's own `schedules` list rides on its row as JSON, like the process lock and the
-- sync-invocation configuration: read whole with the definition, never queried.
--
-- The schedules read from cron.yaml files live in a table of their own, one row per entry and
-- grouped by the source the file came from (classpath:/workflows/cron.yaml, an imported directory, a
-- git repository). A re-import of a source replaces that source's rows wholesale, so an entry removed
-- from the file stops firing; every pod's cron scan reads the same set.
--
-- Plain portable DDL with IF NOT EXISTS, as V29-V36: it also runs over a schema ddl-auto created
-- from the entities (WorkflowDefinitionEntity, CronScheduleEntity).
ALTER TABLE workflow_definition_entity ADD COLUMN IF NOT EXISTS schedules_json TEXT;

CREATE TABLE IF NOT EXISTS cron_schedule (
    id               VARCHAR(64)   NOT NULL PRIMARY KEY,
    source_key       VARCHAR(1024) NOT NULL,
    entry_index      INTEGER       NOT NULL,
    workflow_id      VARCHAR(255)  NOT NULL,
    cron_expression  VARCHAR(255)  NOT NULL,
    zone_id          VARCHAR(64),
    variables_json   TEXT
);

CREATE INDEX IF NOT EXISTS idx_cron_schedule_workflow ON cron_schedule (workflow_id);
