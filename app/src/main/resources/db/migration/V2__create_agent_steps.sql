CREATE TABLE agent_steps (
  step_id VARCHAR(36) NOT NULL,
  run_id VARCHAR(36) NOT NULL,
  step_sequence BIGINT NOT NULL,
  step_type VARCHAR(40) NOT NULL,
  previous_status VARCHAR(24) NOT NULL,
  status VARCHAR(24) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  CONSTRAINT pk_agent_steps PRIMARY KEY (step_id),
  CONSTRAINT fk_agent_steps_run FOREIGN KEY (run_id) REFERENCES agent_runs (run_id),
  CONSTRAINT uk_agent_steps_run_sequence UNIQUE (run_id, step_sequence)
);
