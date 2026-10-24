-- transaction_app: the role the service runs as (k8s/externalsecret-db-app.yaml). Migrations run
-- as transaction_migrator, which owns every table; the service gets only the grants each
-- migration gives it, and never DDL, DELETE or TRUNCATE.
--
-- In a cluster CloudNativePG creates the role, with a login and its password from OpenBao (gitops
-- apps/db/postgres). Here it is created NOLOGIN when missing, so that a laptop's or a test's
-- database has the grantee too, and the grants in later migrations run everywhere the same.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'transaction_app') THEN
        CREATE ROLE transaction_app NOLOGIN;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO transaction_app;
