-- Run as neondb_owner on the shared main branch. No application tables change.
-- Individual LOGIN roles are granted this group separately; no passwords here.
BEGIN;
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'calorica_observer') THEN
        CREATE ROLE calorica_observer NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
            NOREPLICATION NOBYPASSRLS;
    END IF;
END
$$;
GRANT CONNECT ON DATABASE neondb TO calorica_observer;
GRANT USAGE ON SCHEMA calorica, public TO calorica_observer;
GRANT SELECT ON ALL TABLES IN SCHEMA calorica TO calorica_observer;
GRANT SELECT ON public.databasechangelog, public.databasechangeloglock TO calorica_observer;
ALTER DEFAULT PRIVILEGES FOR ROLE neondb_owner IN SCHEMA calorica
    GRANT SELECT ON TABLES TO calorica_observer;
COMMIT;
