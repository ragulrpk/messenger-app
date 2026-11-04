-- Administrators are recorded per account instead of being fixed to one username in code.
-- The account that was the hard-coded administrator keeps the role.
ALTER TABLE app_users ADD COLUMN administrator BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE app_users SET administrator = TRUE WHERE username = 'ragul';

-- A temporary password issued by an administrator must be replaced before anything else is allowed,
-- and the account owner is told when that happened.
ALTER TABLE app_users ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE app_users ADD COLUMN password_reset_at TIMESTAMP WITH TIME ZONE;

-- An administrator's authenticator-app (TOTP) secret, null until enrolled. The last accepted time step
-- stops a code from being used twice.
ALTER TABLE app_users ADD COLUMN totp_secret VARCHAR(64);
ALTER TABLE app_users ADD COLUMN totp_last_step BIGINT;

-- Every administrative action, visible to all administrators. Rows are never updated or deleted.
CREATE TABLE account_audit (
    id UUID PRIMARY KEY,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    actor_id UUID NOT NULL REFERENCES app_users(id),
    target_id UUID REFERENCES app_users(id),
    action VARCHAR(40) NOT NULL
);
CREATE INDEX account_audit_time_idx ON account_audit(occurred_at DESC);
