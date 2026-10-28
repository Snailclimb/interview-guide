ALTER TABLE agent_chat_sessions
    ADD COLUMN loop_trace TEXT;

COMMENT ON COLUMN agent_chat_sessions.loop_trace IS
    'Agent Loop 受控步骤轨迹；仅保存动作、状态、观察摘要、尝试次数和耗时';
