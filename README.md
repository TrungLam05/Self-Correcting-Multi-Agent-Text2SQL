# Self-Correcting Multi-Agent Text2SQL (gRPC-first, hybrid serverless)

A internal analytics copilot:

- One **gRPC** endpoint: `Text2SQLService/ExecuteQuery`
- Converts natural language → safe, verified **SELECT-only SQL**
- Executes on a sandbox analytics DB
- Repairs failures using execution feedback
- Returns results + SQL + confidence + explanation

## Planning (authoritative)

See `docs/sprints.md`.

## Local dev (Role B focus: Platform & Tool Servers)

### 1) Start sandbox Postgres

```bash
cd db
docker compose up -d
```

### 2) Run Spring Boot gRPC server

```bash
cd platform
./mvnw spring-boot:run
```

### 3) Call ExecuteQuery

If you have `grpcurl`:

```bash
grpcurl -plaintext -d '{"question":"Show weekly active users by country for the last 12 weeks"}' localhost:9090 text2sql.v1.Text2SQLService/ExecuteQuery
```

### 4) Schema + executor tool endpoints (HTTP)

- Schema metadata: `GET http://localhost:8080/api/schema`
- Execute SQL (read-only): `POST http://localhost:8080/api/executor/execute`

## Safety (Sprint 1 baseline)

- Read-only DB user
- SELECT-only enforcement
- Auto-LIMIT injection
- Query timeouts
