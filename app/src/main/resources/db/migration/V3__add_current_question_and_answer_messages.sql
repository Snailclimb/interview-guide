ALTER TABLE agent_runs
  ADD COLUMN current_question_id VARCHAR(64);

ALTER TABLE agent_runs
  ADD CONSTRAINT chk_agent_runs_waiting_user_current_question
  CHECK (
    (status = 'WAITING_USER' AND current_question_id IS NOT NULL)
    OR (status <> 'WAITING_USER' AND current_question_id IS NULL)
  );

CREATE TABLE agent_answer_messages (
  answer_message_id VARCHAR(36) NOT NULL,
  run_id VARCHAR(36) NOT NULL,
  message_id VARCHAR(64) NOT NULL,
  answered_question_id VARCHAR(64) NOT NULL,
  content TEXT NOT NULL,
  payload_fingerprint VARCHAR(64) NOT NULL,
  received_at TIMESTAMP NOT NULL,
  CONSTRAINT pk_agent_answer_messages PRIMARY KEY (answer_message_id),
  CONSTRAINT fk_agent_answer_messages_run
    FOREIGN KEY (run_id) REFERENCES agent_runs (run_id),
  CONSTRAINT uk_agent_answer_messages_run_message UNIQUE (run_id, message_id)
);
