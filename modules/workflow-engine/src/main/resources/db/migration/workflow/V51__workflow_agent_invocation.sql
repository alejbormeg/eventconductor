ALTER TABLE step_execution_entity ADD COLUMN IF NOT EXISTS invocation_id VARCHAR(255);
ALTER TABLE step_execution_entity ADD COLUMN IF NOT EXISTS agent_request TEXT;
ALTER TABLE step_execution_entity ADD COLUMN IF NOT EXISTS agent_hold_reason VARCHAR(255);
ALTER TABLE step_execution_entity ADD COLUMN IF NOT EXISTS last_agent_state_sequence BIGINT;
ALTER TABLE step_execution_entity ADD COLUMN IF NOT EXISTS failure_non_retryable BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE process_entity ADD COLUMN IF NOT EXISTS initiating_actor VARCHAR(255);
