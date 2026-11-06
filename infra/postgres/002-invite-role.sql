\set ON_ERROR_STOP on
\getenv invite_password DB_INVITE_PASSWORD
CREATE ROLE finance_invite_admin LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'invite_password';
SELECT format('GRANT CONNECT ON DATABASE %I TO finance_invite_admin', current_database()) \gexec
GRANT USAGE ON SCHEMA app TO finance_invite_admin;
