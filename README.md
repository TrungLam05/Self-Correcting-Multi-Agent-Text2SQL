# Self-Correcting Multi-Agent Text2SQL (REST-first, serverless-friendly)
This is my fork of a collaborative project with 2 other members.
An internal analytics copilot:

- **REST API** endpoints (HTTP/JSON) suitable for AWS API Gateway + Lambda + Step Functions
- Converts natural language → safe, verified **SELECT-only SQL** (pipeline in progress)
- Executes on a sandbox analytics DB with safety guardrails
- Returns results + SQL + confidence + explanation

## Planning (authoritative)

See `docs/sprints.md`.

## Local dev (Role B focus: Platform & Tool Servers)

### 1) Start sandbox Postgres

```bash
cd db
docker compose up -d
```

### 2) Run Spring Boot REST server

```bash
cd platform
./mvnw spring-boot:run
```

### 3) REST endpoints

- Text2SQL: `POST http://localhost:8080/api/text2sql/execute`
	- Body: `{ "question": "..." }`
	- Current behavior: supports a temporary **SQL passthrough** mode for integration testing.
		- If `question` starts with `SELECT` or `WITH`, it will be executed safely.
		- Otherwise returns a "not implemented" response (until the full Text2SQL pipeline is added).
- Schema metadata: `GET http://localhost:8080/api/schema`
- Execute SQL (read-only): `POST http://localhost:8080/api/executor/execute`
	- Body: `{ "sql": "SELECT ..." }`

### 4) Test the system (copy/paste)

Health check:

```bash
curl -sS http://localhost:8080/actuator/health
```

Schema service (Sprint 1 B1):

```bash
# Full schema snapshot (tables + columns)
curl -sS "http://localhost:8080/api/schema"

# One table schema
curl -sS "http://localhost:8080/api/schema/tables/public/users"
```

Sandbox SQL executor (Sprint 1 B2 + B3):

```bash
# Safe SELECT (LIMIT is auto-injected if missing)
curl -sS -X POST "http://localhost:8080/api/executor/execute" \
	-H "Content-Type: application/json" \
	-d '{"sql":"SELECT country, COUNT(*) AS users FROM users GROUP BY country ORDER BY users DESC"}'

# Another SELECT (shows LIMIT enforcement)
curl -sS -X POST "http://localhost:8080/api/executor/execute" \
	-H "Content-Type: application/json" \
	-d '{"sql":"SELECT * FROM events ORDER BY created_at DESC"}' | head

# Non-SELECT should be rejected
curl -sS -i -X POST "http://localhost:8080/api/executor/execute" \
	-H "Content-Type: application/json" \
	-d '{"sql":"DELETE FROM users"}'
```

Text2SQL endpoint (Sprint 1 integration stub):

```bash
# Current behavior is a temporary passthrough mode for integration testing.
# If the question starts with SELECT/WITH, it will be executed safely.
curl -sS -X POST "http://localhost:8080/api/text2sql/execute" \
	-H "Content-Type: application/json" \
	-d '{"question":"SELECT status, COUNT(*) AS n FROM subscriptions GROUP BY status"}'
```

## Safety (Sprint 1 baseline)

- Read-only DB user
- SELECT-only enforcement
- Auto-LIMIT injection
- Query timeouts
