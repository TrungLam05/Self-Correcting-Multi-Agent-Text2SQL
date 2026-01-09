🧑‍🤝‍🧑 Team Roles (Fixed)
Person A — Agent Intelligence (Python + LLMs)
Router · Planner · SQL Generator · Repair · Verifier · Explanation · Inference abstraction

Person B — Platform & Tool Servers (Spring Boot / Java, REST only)
Schema Service · Sandbox SQL Executor · Query History · SQL Sanitization

Person C — Orchestration, Observability, UI & Learning (AWS Serverless)
API Gateway · Step Functions · Lambda · Logging · UI · Learning Loop

🚀 Sprint 1 — End‑to‑End MVP (Week 1)
Theme: Correctness first
Outcome: A REST endpoint can answer basic Text2SQL questions end‑to‑end.

Person A — Agent Intelligence
A1. Router Agent (v1)
As an analyst, I want my natural‑language query classified by basic complexity so that the system can choose the appropriate SQL generation strategy.

A2. Intent Planner Agent
As an analyst, I want my question converted into a structured intent (metrics, filters, groupings, time window) so that SQL generation reflects what I actually meant.

A3. SQL Generator Agent (Basic)
As an analyst, I want the system to generate a valid SELECT‑only SQL query using schema context so that it can be safely executed.

Person B — Platform & Tool Servers (REST)
B1. Schema Tool Service
As a system, I want a Spring Boot REST service that exposes database schema metadata so that all agents reason over a single authoritative schema.

B2. Sandbox SQL Executor Service
As a system, I want a REST service that executes SQL with read‑only credentials, timeouts, and LIMIT enforcement so that generated SQL cannot harm the database.

B3. SQL Safety Filter
As a security reviewer, I want all incoming SQL validated as SELECT‑only so that DDL/DML statements are never executed.

Person C — Orchestration & Entry
C1. API Gateway /query Endpoint
As an analyst, I want a REST endpoint where I can submit a natural‑language question so that I can interact with the system externally.

C2. Step Functions MVP Orchestration
As a developer, I want a Step Functions workflow that orchestrates Router → Planner → Generator → Executor so that queries run end‑to‑end automatically.

C3. Centralized Logging (v1)
As a developer, I want each request logged with latency and status so that system behavior is observable.

🔁 Sprint 2 — Self‑Correction & Safety (Week 2)
Theme: Autonomy & robustness
Outcome: SQL errors are automatically repaired.

Person A — Agent Intelligence
A4. SQL Error Classifier
As a developer, I want SQL execution errors classified so that the correct repair strategy can be selected.

A5. Repair Agent
As an analyst, I want the system to automatically repair failing SQL using error messages and schema context so that I don’t need to debug queries.

A6. Multi‑Candidate SQL (pass@k)
As an analyst, I want multiple SQL candidates generated for complex queries so that execution success rates increase.

Person B — Platform & Tool Servers
B4. Structured Error Responses
As a system, I want SQL execution failures returned with normalized error codes so that agents can reason about failures programmatically.

B5. Hardened SQL Enforcement
As a security reviewer, I want SQL sanitized using a parser so that unsafe queries are always rejected.

B6. Repair Metadata in REST Responses
As an analyst, I want REST responses to include retry counts and repaired SQL so that the system is reviewable.

Person C — Orchestration & Observability
C4. Repair Loop in Step Functions
As a system, I want SQL generation and execution retried up to a fixed limit so that recoverable failures resolve automatically.

C5. Trace ID Propagation
As a developer, I want every request assigned a trace ID so that retries and repairs can be correlated across services.

C6. Attempt‑Level Logging
As a developer, I want every SQL attempt logged so that failure patterns can be analyzed.

🧠 Sprint 3 — Verification, Ranking & MCP Compatibility (Week 3)
Theme: Trust & composability
Outcome: Executable SQL is verified and ranked.

Person A — Agent Intelligence
A7. Rule‑Based SQL Verifier
As an analyst, I want the system to verify that SQL includes required metrics, filters, and groupings so that wrong answers are rejected.

A8. Semantic SQL Scorer
As an analyst, I want SQL candidates scored semantically so that the best answer is selected.

A9. Inference Abstraction Layer
As a developer, I want inference calls abstracted so that lightweight agents can optionally use optimized local models.

Person B — Platform & Tool Servers
B7. Query History REST Service
As an analyst, I want successful past queries stored so that similar future questions can reuse proven patterns.

B8. MCP‑Callable REST Interface
As a platform engineer, I want the REST API callable from an MCP server so that agentic tools can invoke analytics programmatically.

Person C — Routing & Packaging
C7. Stable REST Contract
As a system designer, I want internal routing logic to evolve without breaking the REST API so that downstream consumers remain stable.

C8. Learned Routing (MVP)
As a developer, I want routing decisions influenced by past execution outcomes so that expensive strategies are used only when necessary.

C9. Confidence‑Aware Responses
As an analyst, I want results returned with confidence scores so that I understand how reliable the answer is.

🚀 Sprint 4 — Productionization & Learning (Week 4)
Theme: Enterprise polish
Outcome: Demo‑ready, serverless, production‑style system.

Person A — Agent Intelligence
A10. Prompt Refinement from Logs
As a developer, I want prompts refined using real failure data so that system accuracy improves over time.

A11. Explanation Agent
As an analyst, I want a concise explanation of what the SQL does so that results are interpretable and auditable.

Person B — Platform & REST Hardening
B9. Optional Optimized Inference Backend
As a platform engineer, I want an optional optimized inference backend so that high‑frequency agents run with lower latency.

B10. REST Production Hardening
As a platform engineer, I want rate limits, timeouts, and concurrency controls so that the REST API handles load safely.

Person C — Deployment, UI & Learning
C10. Serverless Production Release
As a system designer, I want API Gateway + Lambda to be the primary production interface so that the system is fully serverless.

C11. UI / Demo Surface
As an analyst, I want a UI that shows SQL, retries, and confidence so that system behavior is transparent.

C12. Execution‑Feedback Learning Loop
As a developer, I want historical execution data to influence routing and scoring so that the system improves over time.