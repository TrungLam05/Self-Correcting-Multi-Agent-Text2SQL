-- Create a dedicated read-only role for the application
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_readonly') THEN
CREATE ROLE app_readonly
    LOGIN
      PASSWORD 'app_readonly_password';
END IF;
END $$;

-- Allow connection to the database
GRANT CONNECT ON DATABASE analytics TO app_readonly;

-- Allow usage of the public schema
GRANT USAGE ON SCHEMA public TO app_readonly;

-- Allow read-only access to all existing tables
GRANT SELECT ON ALL TABLES IN SCHEMA public TO app_readonly;

-- Ensure future tables are also readable
ALTER DEFAULT PRIVILEGES IN SCHEMA public
GRANT SELECT ON TABLES TO app_readonly;
