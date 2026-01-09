-- Create a dedicated read-only role for the application.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_readonly') THEN
    CREATE ROLE app_readonly LOGIN PASSWORD 'app_readonly_password';
  END IF;
END $$;

GRANT CONNECT ON DATABASE analytics TO app_readonly;
