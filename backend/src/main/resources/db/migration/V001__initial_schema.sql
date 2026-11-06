CREATE TABLE app.users
(
    id                 uuid PRIMARY KEY,
    email              varchar(254) NOT NULL CHECK (length(btrim(email)) > 0),
    email_norm         varchar(254) NOT NULL UNIQUE CHECK (length(btrim(email_norm)) > 0),
    password_hash      text         NOT NULL CHECK (length(password_hash) > 0),
    base_currency_code varchar(3)   NOT NULL CHECK (base_currency_code IN ('RUB', 'EUR', 'USD')),
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE app.auth_sessions
(
    id                uuid PRIMARY KEY,
    user_id           uuid        NOT NULL REFERENCES app.users (id),
    access_token_hash varchar(64) NOT NULL UNIQUE CHECK (access_token_hash ~ '^[0-9a-f]{64}$'
) ,
    refresh_token_hash varchar(64) NOT NULL UNIQUE CHECK (refresh_token_hash ~ '^[0-9a-f]{64}$'),
    access_expires_at timestamptz NOT NULL,
    refresh_expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (access_expires_at > created_at),
    CHECK (refresh_expires_at >= access_expires_at)
);
CREATE INDEX auth_sessions_user_active ON app.auth_sessions (user_id) WHERE revoked_at IS NULL;

CREATE TABLE app.used_refresh_tokens
(
    token_hash varchar(64) PRIMARY KEY CHECK (token_hash ~ '^[0-9a-f]{64}$'
) ,
    session_id uuid NOT NULL REFERENCES app.auth_sessions(id),
    used_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    CHECK (expires_at > used_at)
);
CREATE INDEX used_refresh_tokens_session ON app.used_refresh_tokens (session_id);
CREATE INDEX used_refresh_tokens_expiry ON app.used_refresh_tokens (expires_at);

CREATE TABLE app.categories
(
    user_id         uuid        NOT NULL REFERENCES app.users (id),
    id              uuid        NOT NULL,
    name            varchar(80) NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 80),
    normalized_name varchar(80) NOT NULL CHECK (length(btrim(normalized_name)) BETWEEN 1 AND 80),
    archived        boolean     NOT NULL DEFAULT false,
    sort_order      integer     NOT NULL DEFAULT 0,
    version         bigint      NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, id),
    UNIQUE (user_id, normalized_name)
);

CREATE TABLE app.expenses
(
    user_id      uuid        NOT NULL REFERENCES app.users (id),
    id           uuid        NOT NULL,
    category_id  uuid        NOT NULL,
    amount_minor bigint      NOT NULL CHECK (amount_minor > 0),
    occurred_on  date        NOT NULL,
    note         varchar(500),
    version      bigint      NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, id),
    FOREIGN KEY (user_id, category_id) REFERENCES app.categories (user_id, id)
);
CREATE INDEX expenses_user_date_category ON app.expenses (user_id, occurred_on, category_id);
CREATE INDEX expenses_user_category_date ON app.expenses (user_id, category_id, occurred_on);

CREATE TABLE app.monthly_budgets
(
    user_id     uuid        NOT NULL REFERENCES app.users (id),
    id          uuid        NOT NULL,
    month_start date        NOT NULL CHECK (extract(day FROM month_start) = 1),
    limit_minor bigint      NOT NULL CHECK (limit_minor > 0),
    version     bigint      NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, id),
    UNIQUE (user_id, month_start)
);

CREATE TABLE app.category_budgets
(
    user_id     uuid        NOT NULL REFERENCES app.users (id),
    id          uuid        NOT NULL,
    category_id uuid        NOT NULL,
    month_start date        NOT NULL CHECK (extract(day FROM month_start) = 1),
    limit_minor bigint      NOT NULL CHECK (limit_minor > 0),
    version     bigint      NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, id),
    UNIQUE (user_id, month_start, category_id),
    FOREIGN KEY (user_id, category_id) REFERENCES app.categories (user_id, id)
);

CREATE TABLE app.user_sync_state
(
    user_id  uuid PRIMARY KEY REFERENCES app.users (id),
    last_seq bigint NOT NULL DEFAULT 0 CHECK (last_seq >= 0)
);

CREATE TABLE app.sync_operations
(
    user_id      uuid        NOT NULL REFERENCES app.users (id),
    operation_id uuid        NOT NULL,
    request_hash varchar(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'
) ,
    result_status varchar(20) NOT NULL CHECK (result_status IN
        ('APPLIED', 'CONFLICT', 'VALIDATION_ERROR', 'NOT_FOUND')),
    result_payload jsonb NOT NULL CHECK (jsonb_typeof(result_payload) = 'object'),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, operation_id)
);

CREATE TABLE app.sync_changes
(
    user_id        uuid        NOT NULL REFERENCES app.users (id),
    seq            bigint      NOT NULL CHECK (seq > 0),
    entity_type    varchar(20) NOT NULL CHECK (entity_type IN
                                               ('EXPENSE', 'CATEGORY', 'MONTHLY_BUDGET', 'CATEGORY_BUDGET')),
    entity_id      uuid        NOT NULL,
    operation      varchar(6)  NOT NULL CHECK (operation IN ('UPSERT', 'DELETE')),
    entity_version bigint      NOT NULL CHECK (entity_version > 0),
    payload        jsonb,
    created_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, seq),
    CHECK ((operation = 'DELETE' AND payload IS NULL AND entity_type <> 'CATEGORY')
        OR (operation = 'UPSERT' AND payload IS NOT NULL AND jsonb_typeof(payload) = 'object'))
);

-- Fixed, migration-owned table names only; no untrusted SQL identifiers.
DO $migration$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['categories', 'expenses', 'monthly_budgets',
        'category_budgets', 'user_sync_state', 'sync_operations', 'sync_changes']
    LOOP
        EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE app.%I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format($policy$
            CREATE POLICY user_isolation ON app.%I
            USING (user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid)
            WITH CHECK (user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid)
            $policy$, table_name);
    END LOOP;
END
$migration$;

GRANT SELECT, INSERT, UPDATE ON app.users TO finance_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON app.auth_sessions TO finance_runtime;
GRANT SELECT, INSERT, DELETE ON app.used_refresh_tokens TO finance_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON app.categories, app.expenses,
    app.monthly_budgets, app.category_budgets, app.user_sync_state TO finance_runtime;
-- The MVP retains both journals; runtime must not erase or rewrite their history.
GRANT SELECT, INSERT ON app.sync_operations, app.sync_changes TO finance_runtime;
