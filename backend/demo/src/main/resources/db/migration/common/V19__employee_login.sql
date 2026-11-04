-- Employees sign in with their GPF number (stored lower-case as the username), or an assigned username when
-- they have none, and receive one-time codes by email. GPF number, email and mobile are each unique.
ALTER TABLE app_users ADD COLUMN gpf_number VARCHAR(32);
ALTER TABLE app_users ADD COLUMN email VARCHAR(254);
ALTER TABLE app_users ADD COLUMN mobile VARCHAR(10);
ALTER TABLE app_users ADD COLUMN cadre VARCHAR(100);
ALTER TABLE app_users ADD COLUMN last_login_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE app_users ADD CONSTRAINT app_users_gpf_unique UNIQUE (gpf_number);
ALTER TABLE app_users ADD CONSTRAINT app_users_email_unique UNIQUE (email);
ALTER TABLE app_users ADD CONSTRAINT app_users_mobile_unique UNIQUE (mobile);
ALTER TABLE app_users ADD CONSTRAINT app_users_email_normalized CHECK (email = lower(trim(email)));

-- Administrators now confirm an emailed code at every sign-in; the authenticator-app secret is no longer used.
ALTER TABLE app_users DROP COLUMN totp_secret;
ALTER TABLE app_users DROP COLUMN totp_last_step;

-- Emailed one-time codes, stored hashed; they belong to their account. A code is single-use, expires, and allows a few attempts.
CREATE TABLE one_time_codes (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    purpose VARCHAR(20) NOT NULL,
    code_hash VARCHAR(100) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    consumed_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX one_time_codes_user_idx ON one_time_codes(user_id, created_at);

-- Sign-in history for administrators, kept with its account: successes, failures and codes sent. user_id is null for unknown login IDs.
CREATE TABLE sign_in_events (
    id UUID PRIMARY KEY,
    user_id UUID REFERENCES app_users(id) ON DELETE CASCADE,
    login_id VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    outcome VARCHAR(30) NOT NULL,
    client_address VARCHAR(64)
);
CREATE INDEX sign_in_events_user_idx ON sign_in_events(user_id, occurred_at DESC);
CREATE INDEX sign_in_events_time_idx ON sign_in_events(occurred_at DESC);

-- An administrator can archive a group: its history stays readable, but nobody can post.
ALTER TABLE chat_conversations ADD COLUMN archived_at TIMESTAMP WITH TIME ZONE;

-- Audit entries can name what they acted on (a group, an import size), and the server-side employee import
-- runs without a signed-in administrator.
ALTER TABLE account_audit ADD COLUMN detail VARCHAR(200);
ALTER TABLE account_audit ALTER COLUMN actor_id DROP NOT NULL;
