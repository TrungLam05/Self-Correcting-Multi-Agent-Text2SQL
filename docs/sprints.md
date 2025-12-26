# 4-sprint plan (authoritative)

This document is backlog-ready and is the authoritative 4-week plan.

## Team roles (fixed across all sprints)

- **Person A — Agent Intelligence (Python + LLMs)**: Router, Planner, SQL Generator, Repair, Verifier/Scorer, Explanation, inference abstraction
- **Person B — Platform & Tool Servers (Spring Boot / Java)**: Schema service, sandbox SQL executor, query history service, gRPC Text2SQL service, SQL sanitization & limits
- **Person C — Orchestration, Observability, UI & Learning (AWS)**: Step Functions orchestration, Lambda execution, logging/metrics, UI/demo, benchmarks & learning loop

---

## Sprint 1 (Week 1) — End-to-End MVP + gRPC Foundation

Theme: Correctness first

### Person A — Agent Intelligence
- **A1. Router Agent (v1)**
  As an analyst, I want my natural-language query classified by basic complexity so that the system can choose an appropriate SQL generation strategy.
- **A2. Intent Planner Agent**
  As an analyst, I want my question converted into a structured intent (metrics, filters, groupings, time window) so that SQL generation reflects what I actually meant.
- **A3. SQL Generator Agent (basic)**
  As an analyst, I want the system to generate a valid SELECT-only SQL query using schema context so that it can be safely executed.

### Person B — Platform & Tool Servers
- **B1. Schema Tool Server**
  As a system, I want a Spring Boot service that exposes database schema metadata so that all agents reason over a single authoritative schema.
- **B2. Sandbox SQL Executor**
  As a system, I want a Spring Boot SQL execution service that enforces read-only access, limits, and timeouts so that generated SQL cannot harm the database.
- **B3. gRPC Contract & Service Skeleton**
  As a platform engineer, I want a gRPC service with a strongly-typed protobuf contract so that the entire Text2SQL pipeline can be invoked as one callable unit.

### Person C — Orchestration & Entry Surfaces
- **C1. Step Functions MVP Orchestration**
  As a developer, I want a Step Functions workflow that orchestrates Router → Planner → Generator → Executor so that queries run end-to-end automatically.
- **C2. Minimal Client / Demo Entrypoint**
  As an analyst, I want a simple client that calls the gRPC endpoint so that I can test the system without building a full UI.
- **C3. Centralized Logging (v1)**
  As a developer, I want each request logged with timing and outcomes so that system behavior is observable.

**Sprint 1 outcome:** A working gRPC-exposed Text2SQL MVP with sandboxed execution and basic correctness.

---

## Sprint 2 (Week 2) — Self-Correction & Safety Hardening

Theme: Autonomy & robustness

### Person A — Agent Intelligence
- **A4. SQL Error Classification**
  As a developer, I want SQL execution failures classified (syntax, missing column, type mismatch, timeout) so that the correct repair strategy can be applied.
- **A5. Repair Agent**
  As an analyst, I want the system to automatically repair failing SQL using error messages and schema context so that I do not need to debug SQL manually.
- **A6. Multi-Candidate SQL Generation (pass@k)**
  As an analyst, I want multiple SQL candidates generated for complex queries so that the probability of successful execution increases.

### Person B — Platform & Tool Servers
- **B4. Structured Error Responses**
  As a system, I want the SQL executor to return normalized error codes so that agents can reason about failures programmatically.
- **B5. SQL Sanitization & Enforcement**
  As a security reviewer, I want strict SELECT-only enforcement so that unsafe SQL is never executed.
- **B6. gRPC Repair Metadata**
  As an analyst, I want gRPC responses to include retry counts and repaired SQL so that the system is reviewable.

### Person C — Orchestration & Observability
- **C4. Repair Loop in Step Functions**
  As a system, I want SQL generation and execution retried up to a fixed limit so that recoverable failures resolve automatically.
- **C5. Trace IDs & Correlation**
  As a developer, I want each request to emit a trace ID so that retries and repairs can be correlated across services.
- **C6. Attempt-Level Logging**
  As a developer, I want every SQL attempt logged so that failure patterns can be analyzed later.

**Sprint 2 outcome:** A self-correcting Text2SQL pipeline with bounded retries and strong safety guarantees.

---

## Sprint 3 (Week 3) — Verification, Ranking & MCP Compatibility

Theme: Trust & composability

### Person A — Agent Intelligence
- **A7. Rule-Based SQL Verification**
  As an analyst, I want the system to verify required metrics, filters, and groupings so that executable-but-wrong SQL is rejected.
- **A8. Semantic SQL Scoring**
  As an analyst, I want each SQL candidate scored semantically so that the best answer is selected.
- **A9. Inference Abstraction Layer**
  As a developer, I want a pluggable inference interface so that lightweight agents can optionally use optimized local models.

### Person B — Platform & Tool Servers
- **B7. Query History Service**
  As an analyst, I want successful past queries stored so that similar future questions can reuse proven patterns.
- **B8. MCP-Callable gRPC Integration**
  As a platform engineer, I want the gRPC Text2SQL service callable from an MCP server so that agentic tools can invoke analytics programmatically.

### Person C — Routing & Packaging
- **C7. Stable gRPC Boundary**
  As a system designer, I want internal routing and verification logic to evolve without breaking the gRPC interface so that downstream consumers remain stable.
- **C8. Learned Routing (MVP)**
  As a developer, I want routing decisions influenced by historical execution outcomes so that expensive strategies are used only when necessary.
- **C9. Confidence-Aware Responses**
  As an analyst, I want results returned with verifier scores so that I understand how reliable the answer is.

**Sprint 3 outcome:** A verifiable, ranked Text2SQL system exposed via a stable gRPC interface, compatible with MCP tooling.

---

## Sprint 4 (Week 4) — Productionization & Optional Optimization

Theme: Enterprise polish

### Person A — Agent Intelligence
- **A10. Prompt Refinement from Logs**
  As a developer, I want prompts refined using real failure data so that accuracy improves over time.
- **A11. Explanation Agent**
  As an analyst, I want a concise explanation of what the SQL does so that results are interpretable and auditable.

### Person B — Platform & gRPC Hardening
- **B9. Optional Optimized Inference Service**
  As a platform engineer, I want an optional optimized inference backend for high-frequency agents so that latency is reduced when self-hosting is available.
- **B10. gRPC Production Hardening**
  As a platform engineer, I want timeouts, rate limits, and concurrency controls so that the gRPC service handles load safely.

### Person C — Deployment, UI & Learning
- **C10. Production Release Posture**
  As a system designer, I want gRPC to be the primary production interface and REST/UI to be optional so that the system mirrors real enterprise deployments.
- **C11. UI / Demo Surface**
  As an analyst, I want a UI that shows SQL, retries, and confidence so that system behavior is transparent.
- **C12. Execution-Feedback Learning Loop**
  As a developer, I want historical execution data to influence routing and scoring so that the system improves over time.

**Sprint 4 outcome:** A production-style, hybrid serverless, gRPC-first multi-agent Text2SQL system.

---

Final one-line summary:

A hybrid serverless, self-correcting, multi-agent Text2SQL system orchestrated with AWS Step Functions and exposed as a production-grade gRPC service.
