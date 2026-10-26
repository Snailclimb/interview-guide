ALTER TABLE user_accounts
    ADD COLUMN IF NOT EXISTS role VARCHAR(16) NOT NULL DEFAULT 'USER';

CREATE INDEX IF NOT EXISTS idx_user_accounts_role_status
    ON user_accounts (role, status);

CREATE TABLE IF NOT EXISTS security_audit_events (
    id BIGSERIAL PRIMARY KEY,
    actor_user_id VARCHAR(36),
    target_user_id VARCHAR(36),
    action VARCHAR(64) NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    resource_id VARCHAR(128),
    result VARCHAR(32),
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_security_audit_created_at
    ON security_audit_events (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_security_audit_actor_created
    ON security_audit_events (actor_user_id, created_at DESC);
