ALTER TABLE agent_chat_sessions
  ADD COLUMN run_prompt_tokens INTEGER,
  ADD COLUMN run_completion_tokens INTEGER,
  ADD COLUMN run_total_tokens INTEGER,
  ADD COLUMN run_model_calls INTEGER,
  ADD COLUMN run_duration_millis BIGINT,
  ADD COLUMN run_cost NUMERIC(20, 10),
  ADD COLUMN run_currency VARCHAR(8),
  ADD COLUMN run_provider VARCHAR(64),
  ADD COLUMN run_model VARCHAR(128);

COMMENT ON COLUMN agent_chat_sessions.run_prompt_tokens IS '最近一次 Agent 运行的输入 Token 总数';
COMMENT ON COLUMN agent_chat_sessions.run_completion_tokens IS '最近一次 Agent 运行的输出 Token 总数';
COMMENT ON COLUMN agent_chat_sessions.run_total_tokens IS '最近一次 Agent 运行的 Token 总数';
COMMENT ON COLUMN agent_chat_sessions.run_model_calls IS '最近一次 Agent 运行实际模型调用次数，包含重试';
COMMENT ON COLUMN agent_chat_sessions.run_duration_millis IS '最近一次 Agent 运行的模型调用耗时总和';
COMMENT ON COLUMN agent_chat_sessions.run_cost IS '已配置单价时计算出的最近一次 Agent 运行费用';
