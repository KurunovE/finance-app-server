CREATE TABLE app.recovery_state (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    epoch uuid NOT NULL,
    phase varchar(8) NOT NULL CHECK (phase IN ('READY','RECOVERY')),
    changed_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO app.recovery_state(singleton,epoch,phase) VALUES (true,gen_random_uuid(),'READY');
ALTER TABLE app.devices ADD COLUMN synced_epoch uuid;

CREATE TABLE app.sync_recoveries (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
    device_id uuid NOT NULL,
    epoch uuid NOT NULL,
    cutoff_seq bigint NOT NULL CHECK (cutoff_seq>=0),
    delivered_seq bigint NOT NULL DEFAULT 0 CHECK (delivered_seq>=0 AND delivered_seq<=cutoff_seq),
    acknowledged_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (user_id,device_id) REFERENCES app.devices(user_id,id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX sync_recoveries_pending_device ON app.sync_recoveries(user_id,device_id,epoch)
    WHERE acknowledged_at IS NULL;
ALTER TABLE app.sync_recoveries ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.sync_recoveries FORCE ROW LEVEL SECURITY;
CREATE POLICY user_isolation ON app.sync_recoveries
    USING (user_id=NULLIF(current_setting('app.current_user_id',true),'')::uuid)
    WITH CHECK (user_id=NULLIF(current_setting('app.current_user_id',true),'')::uuid);
GRANT SELECT ON app.recovery_state TO finance_runtime;
GRANT SELECT,INSERT,UPDATE,DELETE ON app.sync_recoveries TO finance_runtime;
GRANT UPDATE (synced_epoch) ON app.devices TO finance_runtime;

-- This lock namespace is shared with runtime writers. Functions are admin-only.
CREATE FUNCTION app.begin_recovery() RETURNS uuid LANGUAGE plpgsql
SET search_path=pg_catalog,app AS $recovery$
DECLARE next_epoch uuid := gen_random_uuid();
BEGIN
    PERFORM pg_advisory_xact_lock(1977041921);
    INSERT INTO app.recovery_state(singleton,epoch,phase) VALUES (true,next_epoch,'RECOVERY')
    ON CONFLICT(singleton) DO UPDATE SET epoch=EXCLUDED.epoch,phase='RECOVERY',changed_at=now();
    UPDATE app.auth_sessions SET revoked_at=coalesce(revoked_at,now()),updated_at=now();
    DELETE FROM app.account_tokens;
    UPDATE app.devices SET synced_epoch=NULL;
    TRUNCATE app.sync_recoveries;
    RETURN next_epoch;
END
$recovery$;
REVOKE ALL ON FUNCTION app.begin_recovery() FROM PUBLIC;

CREATE FUNCTION app.activate_recovery(expected_epoch uuid) RETURNS void LANGUAGE plpgsql
SET search_path=pg_catalog,app AS $recovery$
BEGIN
    PERFORM pg_advisory_xact_lock(1977041921);
    UPDATE app.recovery_state SET phase='READY',changed_at=now()
    WHERE singleton AND epoch=expected_epoch AND phase='RECOVERY';
    IF NOT FOUND THEN RAISE EXCEPTION 'Recovery epoch or phase mismatch'; END IF;
END
$recovery$;
REVOKE ALL ON FUNCTION app.activate_recovery(uuid) FROM PUBLIC;
