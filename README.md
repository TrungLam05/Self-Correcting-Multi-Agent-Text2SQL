# Self-Correcting Multi-Agent Text2SQL

An internal analytics copilot that converts natural language questions into safe, verified SQL queries. Built as a 3-person collaborative project across four sprints.

- **REST API** endpoints (HTTP/JSON) suitable for AWS API Gateway + Lambda + Step Functions
- Converts natural language to safe, verified **SELECT-only SQL**
- Executes on a sandbox analytics DB with multi-layer safety guardrails
- Returns results + SQL + confidence + explanation
- Self-correcting: automatically classifies and repairs failing SQL

---

## Architecture

```
                         ┌──────────────────────┐
                         │   Streamlit UI        │
                         │   (app.py)            │
                         └──────────┬───────────┘
                                    │
               ┌────────────────────┼────────────────────┐
               │ LOCAL              │              CLOUD  │
               │                   │                     │
               ▼                   │               ▼     │
        run_pipeline.py            │        API Gateway   │
        (local orchestration)      │            │        │
               │                   │        Step Functions│
               │                   │            │        │
               ▼                   │            ▼        │
    ┌──────────────────────────────┴─────────────────────┤
    │         Agent Intelligence (Python)                 │
    │                                                     │
    │  Router ─► Intent Planner ─► SQL Generator          │
    │                                  │                  │
    │                          ┌───────┴────────┐         │
    │                          │  single   multi │         │
    │                          │  candidate path │         │
    │                          └───────┬────────┘         │
    │                                  │                  │
    │                    SQL Verifier (AST) ─► Scorer      │
    │                                  │                  │
    │                          Explanation Agent           │
    │                                                     │
    │              Error Classifier ─► Repair Agent        │
    └──────────────────┬──────────────────────────────────┘
                       │
    ┌──────────────────▼──────────────────────────────────┐
    │         Platform & Tool Servers (Spring Boot)        │
    │                                                      │
    │  GET  /api/schema          Schema metadata           │
    │  POST /api/executor/execute   Safe SQL execution     │
    │  POST /api/text2sql/execute   Text2SQL endpoint      │
    │  GET  /api/history            Query history           │
    │  POST /api/history            Save query              │
    └──────────────────┬──────────────────────────────────┘
                       │
    ┌──────────────────▼──────────────────────────────────┐
    │         Postgres 16 (Docker)                         │
    │                                                      │
    │  Tables: users, events, subscriptions, sessions      │
    │  Role: app_readonly (SELECT-only)                    │
    │  Table: query_history (for learning loop)            │
    └─────────────────────────────────────────────────────┘
```

### Team Roles

| Role | Owner | Scope |
|------|-------|-------|
| **A -- Agent Intelligence** | Python + LLMs | Router, Planner, Generator, Verifier, Scorer, Repair, Explanation, Inference |
| **B -- Platform & Tool Servers** | Spring Boot / Java | Schema Service, SQL Executor, Safety Policy, History, Production Hardening |
| **C -- Orchestration & UI** | AWS Serverless | API Gateway, Step Functions, Lambda, Streamlit UI |

---

## Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| **Docker** | 20+ | Sandbox Postgres database |
| **Java** | 17+ | Spring Boot platform server |
| **Maven** | (bundled) | Build via `./mvnw` wrapper |
| **Python** | 3.11+ | AI agents |
| **OpenAI API key** | -- | LLM inference (gpt-5-mini) |
| **AWS CLI** | 2.x (optional) | Cloud deployment verification |

---

## Quick Start (Local Mode)

Run the entire pipeline on your machine without any AWS dependencies.

### 1. Start the database

```bash
cd db
docker compose up -d
```

Verify it's healthy:

```bash
docker compose ps
# Should show text2sql-postgres as "healthy"
```

### 2. Start Spring Boot platform

```bash
cd platform
./mvnw spring-boot:run
```

On Windows use `mvnw.cmd spring-boot:run`.

Verify:

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP"}
```

### 3. Install Python dependencies

```bash
cd text2sql-agents
pip install -r requirements.txt
```

### 4. Set your OpenAI API key

```bash
# Linux/macOS
export OPENAI_API_KEY=sk-...

# Windows PowerShell
$env:OPENAI_API_KEY="sk-..."
```

### 5. Run the pipeline

```bash
# Single question
python run_pipeline.py "What is our total revenue?"

# Interactive mode
python run_pipeline.py --interactive
```

The script will:
1. Fetch the real database schema from Spring Boot
2. Classify query complexity (Router)
3. Extract structured intent (Intent Planner via LLM)
4. Generate SQL with verification and explanation (SQL Generator + Verifier + Scorer)
5. Execute the SQL safely via Spring Boot (with LIMIT enforcement, timeouts)
6. If execution fails: classify error, repair SQL, and retry (up to 3 attempts)
7. Save successful queries to history
8. Print full results with SQL, explanation, confidence, and data

---

## Cloud Mode (AWS)

The same agent code runs on AWS Lambda, orchestrated by Step Functions.

### Lambda Functions

| Lambda | Handler Entry Point |
|--------|-------------------|
| Router | `agents.lambda_adapter.router_handler` |
| Intent Planner | `agents.lambda_adapter.intent_planner_handler` |
| SQL Generator | `agents.lambda_adapter.sql_generator_handler_with_explanation` |
| Error Classifier | `agents.lambda_adapter.error_classifier_handler` |
| Repair Agent | `agents.lambda_adapter.repair_agent_handler` |

### Step Functions Flow

```
Start
  │
  ▼
FetchSchema (HTTP: GET /api/schema)
  │
  ▼
RouterLambda
  │
  ▼
IntentPlannerLambda
  │
  ▼
SqlGeneratorLambda
  │
  ▼
ExecuteSQL (HTTP: POST /api/executor/execute)
  │
  ├── Success ──► SaveHistory ──► End
  │
  └── Failure ──► ErrorClassifierLambda
                       │
                       ▼
                  RepairAgentLambda
                       │
                       ▼
                  ExecuteSQL (retry, max 3)
```

### API Gateway

The public endpoint is accessible at:

```
POST https://<api-gateway-id>.execute-api.us-east-1.amazonaws.com/dev/query
Content-Type: application/json

{"user_query": "Show me total sales by country"}
```

### Streamlit UI

```bash
pip install streamlit requests
streamlit run app.py
```

Update the `API_URL` in `app.py` to point to your API Gateway endpoint.

### Verifying the AWS deployment

```bash
# List Lambda functions
aws lambda list-functions --region us-east-1 \
  --query "Functions[?contains(FunctionName, 'text2sql')]"

# List Step Functions
aws stepfunctions list-state-machines --region us-east-1

# Test the endpoint
curl -X POST https://<your-api-gateway-url>/dev/query \
  -H "Content-Type: application/json" \
  -d '{"user_query": "How many users do we have?"}'
```

---

## REST API Reference

All endpoints are served by Spring Boot on `http://localhost:8080`.

### `GET /api/schema`

Returns the full database schema (tables, columns, types, PKs, FKs, indexes).

```bash
curl -s http://localhost:8080/api/schema | python -m json.tool
```

### `GET /api/schema/tables/{schema}/{table}`

Returns schema for a single table.

```bash
curl -s http://localhost:8080/api/schema/tables/public/users
```

### `POST /api/executor/execute`

Executes a SQL query with safety enforcement.

```bash
curl -s -X POST http://localhost:8080/api/executor/execute \
  -H "Content-Type: application/json" \
  -d '{"sql": "SELECT country, COUNT(*) AS users FROM users GROUP BY country ORDER BY users DESC"}'
```

Response includes: `sanitizedSql`, `columns`, `rows`, `truncated`, `rowCount`, `executionTimeMs`, `error` (if any), `repairMetadata`, `retryCount`.

### `POST /api/text2sql/execute`

Text2SQL endpoint (passthrough mode for integration testing).

```bash
curl -s -X POST http://localhost:8080/api/text2sql/execute \
  -H "Content-Type: application/json" \
  -d '{"question": "SELECT status, COUNT(*) AS n FROM subscriptions GROUP BY status"}'
```

### `GET /api/history`

Returns the 50 most recent successful queries.

```bash
curl -s http://localhost:8080/api/history
```

### `POST /api/history`

Saves a query to history.

```bash
curl -s -X POST http://localhost:8080/api/history \
  -H "Content-Type: application/json" \
  -d '{"natural_language_query": "Total users", "generated_sql": "SELECT COUNT(*) FROM users;", "execution_time_ms": 12}'
```

### `GET /actuator/health`

Health check.

```bash
curl -s http://localhost:8080/actuator/health
```

---

## Agent Pipeline Reference

Each agent is a self-contained Python module in `text2sql-agents/agents/`.

| Agent | File | Sprint | Description |
|-------|------|--------|-------------|
| **Router** | `router.py` | A1 | Rule-based heuristic that classifies query complexity as `simple` or `moderate`, selecting `single_candidate` or `multi_candidate` strategy |
| **Intent Planner** | `intent_planner.py` | A2 | LLM-based extraction of structured intent: metric, aggregation, filters, group_by, order_by, limit, tables |
| **SQL Generator** | `sql_generator.py` | A3 | LLM-based SQL generation from structured intent. SELECT-only, with safety validation |
| **Multi-Candidate** | `sql_generator.py` | A6 | Generates N SQL candidates for complex queries using `n=` parameter |
| **Error Classifier** | `error_classifier.py` | A4 | Classifies Postgres errors into SCHEMA, SYNTAX, LOGIC, or UNKNOWN |
| **Repair Agent** | `repair_agent.py` | A5 | LLM-based SQL repair using error context and schema |
| **SQL Verifier** | `sql_verifier.py` | A7 | AST-based (sqlglot) verification that SQL matches the structured intent |
| **Semantic Scorer** | `semantic_scorer.py` | A8 | LLM-as-judge that scores candidates 0-10 for semantic correctness |
| **Inference Layer** | `inference.py` | A9 | Centralized OpenAI client with retries, backoff, and support for custom base URLs |
| **Prompt Optimizer** | `prompt_optimizer.py` | A10 | Analyzes failure logs and suggests prompt improvements |
| **Explanation** | `sql_generator.py` | A11 | Generates business-friendly SQL explanations |

### Shared Contracts

All agent data models are defined in `text2sql-agents/shared/contracts.py` using Pydantic:

- `DatabaseSchema`, `SchemaTable`, `SchemaColumn`
- `QueryIntent`, `FilterCondition`, `OrderByClause`
- `RouterOutput`, `SQLOutput`, `MultiCandidateOutput`
- `ErrorType`, `RepairInput`, `RepairOutput`
- `SqlVerificationResult`, `SqlFacts`, `SqlVerificationIssue`
- `ExplanationOutput`, `FailureLog`

### Inference Abstraction

All LLM calls go through `agents/inference.py`, which supports:

- **OpenAI** (default): no extra config needed
- **Local vLLM**: `INFERENCE_BASE_URL=http://localhost:8000/v1`
- **Ollama**: `INFERENCE_BASE_URL=http://localhost:11434/v1`

Set `INFERENCE_API_KEY` to override `OPENAI_API_KEY` for custom providers.

---

## Safety Layers

Three layers of defense prevent harmful SQL from reaching the database:

| Layer | Where | What |
|-------|-------|------|
| **1. Python validation** | `sql_generator.py` | `validate_sql_safety()` -- regex-based SELECT-only check, forbidden keyword detection |
| **2. Java AST enforcement** | `SqlSafetyPolicy.java` | JSQLParser-based parsing: rejects non-SELECT, multi-statement, locking clauses; auto-injects/clamps LIMIT |
| **3. Database role** | Postgres | `app_readonly` role with only SELECT privileges |

---

## Production Hardening (B10)

The Spring Boot platform includes production-grade protections:

### Rate Limiting

Per-IP rate limiting on `/api/executor/**` and `/api/text2sql/**` endpoints. Returns `429 Too Many Requests` with `Retry-After` header when exceeded.

Configuration in `application.yaml`:

```yaml
text2sql:
  rate-limit:
    requestsPerMinute: 30  # per IP
```

### Concurrency Controls

Limits concurrent SQL executions to protect the database connection pool. Returns `503 Service Unavailable` when all permits are taken.

```yaml
text2sql:
  concurrency:
    maxConcurrentQueries: 4  # max simultaneous SQL executions
```

### Timeouts

```yaml
text2sql:
  executor:
    queryTimeoutSeconds: 5   # per-query timeout
  db:
    queryTimeoutSeconds: 5   # JDBC-level timeout
```

### SQL Size Limits

```yaml
text2sql:
  executor:
    maxSqlLength: 20000      # reject oversized SQL
    defaultLimit: 200         # auto-injected LIMIT
    maxLimit: 1000            # max allowed LIMIT
```

---

## Database

The sandbox database runs in Docker (Postgres 16).

### Schema

| Table | Purpose |
|-------|---------|
| `users` | User accounts (country, signup_date) |
| `events` | User activity events (event_name, created_at) |
| `subscriptions` | Subscription status and MRR |
| `sessions` | User sessions (device_type, started_at) |
| `query_history` | Stores successful past queries for learning |

### Seed Data

- 200 users across 5 countries
- 3,000 events (open_app, purchase, view_item, add_to_cart)
- Subscriptions (60% active, 40% canceled)
- 1,500 sessions (iOS, Android, Web)

### Reset

```bash
cd db
docker compose down -v
docker compose up -d
```

---

## Project Structure

```
.
├── app.py                          # Streamlit UI (talks to AWS API Gateway)
├── run_pipeline.py                 # Local end-to-end orchestration script
├── README.md
│
├── text2sql-agents/                # Role A: Python AI Agents
│   ├── requirements.txt
│   ├── general_test.py             # Demo test script
│   ├── shared/
│   │   └── contracts.py            # Pydantic data models
│   └── agents/
│       ├── router.py               # A1: Complexity classification
│       ├── intent_planner.py       # A2: NL -> structured intent
│       ├── sql_generator.py        # A3/A6/A11: SQL generation + explanation
│       ├── sql_verifier.py         # A7: AST-based verification
│       ├── semantic_scorer.py      # A8: LLM-as-judge scoring
│       ├── error_classifier.py     # A4: Error classification
│       ├── repair_agent.py         # A5: SQL repair
│       ├── inference.py            # A9: Centralized LLM inference
│       ├── prompt_optimizer.py     # A10: Failure analysis
│       ├── optimize_prompt.py      # A10: CLI runner
│       └── lambda_adapter.py       # Lambda handler wrappers
│
├── platform/                       # Role B: Spring Boot Platform
│   ├── pom.xml
│   ├── mvnw / mvnw.cmd
│   └── src/
│       ├── main/
│       │   ├── resources/application.yaml
│       │   └── java/.../platform/
│       │       ├── config/         # DB, schema, executor properties
│       │       ├── schema/         # B1: Schema metadata service
│       │       ├── executor/       # B2/B3/B4/B5: Safe SQL execution
│       │       ├── history/        # B7: Query history
│       │       ├── text2sql/       # Text2SQL endpoint
│       │       └── web/            # Exception handler, rate limit, concurrency
│       └── test/                   # JUnit test suite
│
├── db/                             # Sandbox Database
│   ├── docker-compose.yml
│   └── init/
│       ├── 00_roles.sql            # Read-only role
│       ├── 10_schema.sql           # Table definitions
│       ├── 20_seed.sql             # Sample data
│       └── 30_history.sql          # Query history table
│
└── docs/                           # Sprint planning & deliverables
    ├── sprints.md                  # Authoritative sprint plan
    └── Sprint*.docx/pdf            # Per-sprint deliverable docs
```

---

## Sprint Completion Summary

| Sprint | Theme | Status |
|--------|-------|--------|
| **1** | End-to-End MVP | All A/B/C stories complete |
| **2** | Self-Correction & Safety | All A/B/C stories complete |
| **3** | Verification, Ranking & MCP | All A/B stories complete; C partial |
| **4** | Productionization & Learning | A10-A11 complete; B10 complete; C10-C11 complete |

See `docs/sprints.md` for the full user story breakdown.
