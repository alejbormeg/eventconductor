-- AGENT steps (2.34): durable invocations of agents published in the IA catalogue.
--
-- Was V51 on the contributor's branch; renumbered to V57 because main had already shipped V53-V56.1 and
-- Flyway refuses an out-of-order version on a database that is past it.
--
-- Plain portable DDL with IF NOT EXISTS, as V39-V56: it also runs over a schema ddl-auto created from the
-- entities. Rolling-upgrade safe: only new nullable (or constant-default) columns, which PostgreSQL 11+ adds
-- without rewriting the table; an engine of 2.33 or earlier never reads them. No index: an invocation is
-- always reached through its step execution's primary key.

-- The invocation of an AGENT step execution: its id (stable across redeliveries, renewed only by an explicit
-- restart), the request rendered once before the first dispatch (sealed like any blob when the process has
-- sensitive variables), why it is held for review (TIMEOUT, CANCELLED, END; null for a runtime-requested
-- review) and the last state sequence accepted from the runtime (older or duplicate replies are dropped).
ALTER TABLE step_execution_entity ADD COLUMN IF NOT EXISTS invocation_id VARCHAR(255);
ALTER TABLE step_execution_entity ADD COLUMN IF NOT EXISTS agent_request TEXT;
ALTER TABLE step_execution_entity ADD COLUMN IF NOT EXISTS agent_hold_reason VARCHAR(255);
ALTER TABLE step_execution_entity ADD COLUMN IF NOT EXISTS last_agent_state_sequence BIGINT;
-- Whether the step's failure was final: kept so a failure held back by another branch's review keeps its
-- retry decision until the review is resolved.
ALTER TABLE step_execution_entity ADD COLUMN IF NOT EXISTS failure_non_retryable BOOLEAN NOT NULL DEFAULT FALSE;

-- Audit only: the stable subject that started the process (never a credential); passed to the agent runtime.
ALTER TABLE process_entity ADD COLUMN IF NOT EXISTS initiating_actor VARCHAR(255);
