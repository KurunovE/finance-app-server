ALTER TABLE app.users ADD COLUMN email_verified_at timestamptz;
CREATE TABLE app.account_tokens (
    token_hash varchar(64) PRIMARY KEY CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    user_id uuid NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
    purpose varchar(16) NOT NULL CHECK (purpose IN ('VERIFY_EMAIL','RESET_PASSWORD')),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL CHECK (expires_at > created_at),
    used_at timestamptz,
    UNIQUE (user_id,purpose)
);
GRANT SELECT,INSERT,UPDATE,DELETE ON app.account_tokens TO finance_runtime;
-- No existing email has been proved by the old invite-only flow.
UPDATE app.auth_sessions SET revoked_at=coalesce(revoked_at,now()),updated_at=now();
