CREATE TABLE query_history (
                               id SERIAL PRIMARY KEY,
                               natural_language_query TEXT NOT NULL,
                               generated_sql TEXT NOT NULL,
                               status VARCHAR(50) DEFAULT 'verified',
                               execution_time_ms INTEGER,
                               created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

GRANT ALL PRIVILEGES ON TABLE query_history TO app_readonly;
GRANT USAGE, SELECT ON SEQUENCE query_history_id_seq TO app_readonly;