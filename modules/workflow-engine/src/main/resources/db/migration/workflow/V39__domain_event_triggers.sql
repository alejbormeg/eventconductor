-- Domain events in: event triggers on a definition and WAIT_FOR_MESSAGE steps fed by a domain event.
--
-- A definition's own `triggers` list rides on its row as JSON, like its `schedules`.
--
-- inbound_event_receipt is the idempotency record and the "received events" listing: one row per
-- receiver (a trigger of a definition, or the message a waiting step turned the event into) and event
-- (CloudEvents source + id). The key is a SHA-256 of the three, so the uniqueness check is one
-- primary-key lookup on every database whatever the length of the parts. Rows older than
-- workflow.events.receipts.retention are purged.
--
-- event_trigger_pause keeps paused triggers by "<workflow>/<trigger>", apart from the definition
-- (whose row every import rewrites), as cron_schedule_pause does for schedules.
--
-- Plain portable DDL with IF NOT EXISTS, as V29-V38: it also runs over a schema ddl-auto created
-- from the entities (WorkflowDefinitionEntity, InboundEventReceiptEntity, EventTriggerPauseEntity).
ALTER TABLE workflow_definition_entity ADD COLUMN IF NOT EXISTS triggers_json TEXT;

CREATE TABLE IF NOT EXISTS inbound_event_receipt (
    receipt_key      VARCHAR(64)   NOT NULL PRIMARY KEY,
    receiver         VARCHAR(1024) NOT NULL,
    receiver_kind    VARCHAR(16)   NOT NULL,
    destination      VARCHAR(255),
    event_type       VARCHAR(512),
    event_source     VARCHAR(1024),
    event_id         VARCHAR(512),
    outcome          VARCHAR(16)   NOT NULL,
    workflow_id      VARCHAR(255),
    trigger_id       VARCHAR(160),
    business_key     VARCHAR(1024),
    message_name     VARCHAR(255),
    correlation_key  VARCHAR(1024),
    detail           VARCHAR(2000),
    received_at      TIMESTAMP     NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_inbound_event_receipt_received_at ON inbound_event_receipt (received_at);

CREATE TABLE IF NOT EXISTS event_trigger_pause (
    trigger_key  VARCHAR(420) NOT NULL PRIMARY KEY,
    paused_at    TIMESTAMP    NOT NULL,
    paused_by    VARCHAR(255)
);
