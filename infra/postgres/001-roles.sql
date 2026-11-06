\set ON_ERROR_STOP on
\getenv runtime_password DB_RUNTIME_PASSWORD
\getenv migrator_password DB_MIGRATOR_PASSWORD

-- Executed once by the PostgreSQL image, as the bootstrap administrator.
CREATE ROLE finance_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'migrator_password';
CREATE ROLE finance_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'runtime_password';
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', current_database()) \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO finance_migrator, finance_runtime', current_database()) \gexec
REVOKE ALL ON SCHEMA public FROM PUBLIC;
CREATE SCHEMA app AUTHORIZATION finance_migrator;
GRANT USAGE ON SCHEMA app TO finance_runtime;
