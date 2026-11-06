ALTER TABLE app.users ADD COLUMN status varchar(8) NOT NULL DEFAULT 'ACTIVE'
    CHECK (status IN ('ACTIVE', 'DISABLED'));

CREATE TABLE app.registration_invites (
    code_hash varchar(64) PRIMARY KEY CHECK (code_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL CHECK (expires_at > created_at),
    used_at timestamptz,
    used_by uuid REFERENCES app.users(id) ON DELETE SET NULL
);
CREATE TABLE app.devices (
    user_id uuid NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
    id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, id)
);

-- Old opaque access sessions cannot be converted to signed JWTs. Revoke them,
-- preserving their metadata and the financial data; users can log in again.
ALTER TABLE app.auth_sessions RENAME COLUMN id TO family_id;
ALTER TABLE app.auth_sessions DROP COLUMN access_token_hash;
ALTER TABLE app.auth_sessions ADD COLUMN device_id uuid;
ALTER TABLE app.auth_sessions ADD COLUMN access_jti uuid NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE app.auth_sessions ADD UNIQUE (access_jti);
INSERT INTO app.devices(user_id,id) SELECT user_id,family_id FROM app.auth_sessions;
UPDATE app.auth_sessions SET device_id=family_id, revoked_at=coalesce(revoked_at,now());
ALTER TABLE app.auth_sessions ALTER COLUMN device_id SET NOT NULL;
ALTER TABLE app.auth_sessions ADD FOREIGN KEY (user_id,device_id)
    REFERENCES app.devices(user_id,id) ON DELETE CASCADE;
CREATE UNIQUE INDEX auth_sessions_one_active_device ON app.auth_sessions(user_id,device_id)
    WHERE revoked_at IS NULL;
ALTER TABLE app.used_refresh_tokens RENAME COLUMN session_id TO family_id;

-- Upgrade the V1 account-owned references without rewriting V1/checksums.
DO $migration$
DECLARE fk record;
BEGIN
    FOR fk IN SELECT c.conrelid::regclass AS relation, c.conname,
                     pg_get_constraintdef(c.oid) AS definition
              FROM pg_constraint c JOIN pg_namespace n ON n.oid=c.connamespace
              WHERE n.nspname='app' AND c.contype='f' AND c.confdeltype='a'
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', fk.relation, fk.conname);
        EXECUTE format('ALTER TABLE %s ADD CONSTRAINT %I %s ON DELETE CASCADE',
                       fk.relation, fk.conname, fk.definition);
    END LOOP;
END
$migration$;

CREATE FUNCTION app.revoke_disabled_user_sessions() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, app AS $function$
BEGIN
    UPDATE app.auth_sessions SET revoked_at=coalesce(revoked_at,now()), updated_at=now()
    WHERE user_id=NEW.id;
    RETURN NEW;
END
$function$;
REVOKE ALL ON FUNCTION app.revoke_disabled_user_sessions() FROM PUBLIC;
CREATE TRIGGER disable_user_sessions AFTER UPDATE OF status ON app.users
    FOR EACH ROW WHEN (NEW.status='DISABLED' AND OLD.status IS DISTINCT FROM NEW.status)
    EXECUTE FUNCTION app.revoke_disabled_user_sessions();

GRANT SELECT ON app.registration_invites TO finance_runtime;
GRANT UPDATE (used_at,used_by) ON app.registration_invites TO finance_runtime;
GRANT SELECT, INSERT ON app.registration_invites TO finance_invite_admin;
GRANT SELECT, INSERT ON app.devices TO finance_runtime;
GRANT DELETE ON app.users TO finance_runtime;
