ALTER TABLE agent_steps
  ADD COLUMN current_question_id VARCHAR(64);

CREATE TABLE agent_checkpoints (
  checkpoint_id VARCHAR(36) NOT NULL,
  run_id VARCHAR(36) NOT NULL,
  last_applied_step_sequence BIGINT NOT NULL,
  recovery_state TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  CONSTRAINT pk_agent_checkpoints PRIMARY KEY (checkpoint_id),
  CONSTRAINT uk_agent_checkpoints_run UNIQUE (run_id),
  CONSTRAINT fk_agent_checkpoints_run
    FOREIGN KEY (run_id) REFERENCES agent_runs (run_id),
  CONSTRAINT fk_agent_checkpoints_last_applied_step
    FOREIGN KEY (run_id, last_applied_step_sequence)
    REFERENCES agent_steps (run_id, step_sequence)
);
