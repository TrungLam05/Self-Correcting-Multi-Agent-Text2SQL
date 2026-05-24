#!/usr/bin/env python3
"""
FastAPI server exposing the Text2SQL pipeline as an HTTP API.

This provides the same functionality as run_pipeline.py but as a REST endpoint,
replacing the need for AWS API Gateway + Step Functions for local/single-server deployment.

Prerequisites:
  1. Postgres running:       cd db && docker compose up -d
  2. Spring Boot running:    cd platform && ./mvnw spring-boot:run
  3. Python deps installed:  pip install -r requirements.txt
  4. OpenAI key exported:    export OPENAI_API_KEY=sk-...

Usage:
  uvicorn api_server:app --host 0.0.0.0 --port 8000 --reload
"""

import os
import sys
import time

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from typing import Any, Optional

import requests as http_requests

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "text2sql-agents"))

from dotenv import load_dotenv
load_dotenv()

PLATFORM_BASE_URL = os.getenv("PLATFORM_URL", "http://localhost:8080")
MAX_REPAIR_ATTEMPTS = 3

app = FastAPI(
    title="Text2SQL API",
    description="Self-Correcting Multi-Agent Text2SQL Pipeline",
    version="1.0.0",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


class QueryRequest(BaseModel):
    user_query: str


class QueryResponse(BaseModel):
    question: str
    sql: str
    explanation: dict
    confidence: float
    attempts: int
    execution_result: Optional[dict] = None
    pipeline_time_ms: int
    error: Optional[str] = None


def fetch_schema() -> dict:
    resp = http_requests.get(f"{PLATFORM_BASE_URL}/api/schema", timeout=10)
    resp.raise_for_status()
    snapshot = resp.json()

    tables = []
    for t in snapshot.get("tables", []):
        columns = []
        for c in t.get("columns", []):
            columns.append({
                "name": c["name"],
                "type": c["dataType"],
                "nullable": c.get("nullable", True),
                "primary_key": c["name"] in (t.get("primaryKeyColumns") or []),
                "foreign_key": None,
            })
        for fk in t.get("foreignKeys", []):
            for col in columns:
                if col["name"] == fk.get("columnName"):
                    col["foreign_key"] = f"{fk.get('referencedTable')}.{fk.get('referencedColumn')}"
        tables.append({"name": t["name"], "columns": columns})

    return {"tables": tables}


def execute_sql(sql: str) -> dict:
    resp = http_requests.post(
        f"{PLATFORM_BASE_URL}/api/executor/execute",
        json={"sql": sql},
        timeout=15,
    )
    resp.raise_for_status()
    return resp.json()


def save_to_history(question: str, sql: str, time_ms: int) -> None:
    try:
        http_requests.post(
            f"{PLATFORM_BASE_URL}/api/history",
            json={
                "natural_language_query": question,
                "generated_sql": sql,
                "execution_time_ms": time_ms,
            },
            timeout=5,
        )
    except Exception:
        pass


@app.get("/health")
def health():
    return {"status": "UP"}


@app.post("/query", response_model=QueryResponse)
def query(req: QueryRequest):
    if not os.getenv("OPENAI_API_KEY"):
        raise HTTPException(status_code=500, detail="OPENAI_API_KEY not configured")

    from agents.lambda_adapter import (
        router_handler,
        intent_planner_handler,
        sql_generator_handler_with_explanation,
        error_classifier_handler,
        repair_agent_handler,
    )

    pipeline_start = time.time()
    question = req.user_query

    # Step 0: Fetch schema
    try:
        schema = fetch_schema()
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"Platform unreachable: {e}")

    # Step 1: Router
    router_result = router_handler({"user_query": question})
    if router_result.get("error"):
        raise HTTPException(status_code=422, detail=router_result["message"])
    decision = router_result["router_decision"]

    # Step 2: Intent Planner
    intent_result = intent_planner_handler({
        "user_query": question,
        "router_decision": decision,
        "schema": schema,
    })
    if intent_result.get("error"):
        raise HTTPException(status_code=422, detail=intent_result["message"])
    intent = intent_result["intent"]

    # Step 3: SQL Generator + Verification + Explanation
    gen_result = sql_generator_handler_with_explanation({
        "user_query": question,
        "router_decision": decision,
        "intent": intent,
        "schema": schema,
    })
    if gen_result.get("error"):
        raise HTTPException(status_code=422, detail=gen_result["message"])
    generated_sql = gen_result["generated_sql"]
    explanation = gen_result.get("explanation", {})

    # Step 4: Execute with repair loop
    attempt = 0
    exec_result = None
    final_sql = generated_sql

    while attempt < MAX_REPAIR_ATTEMPTS:
        attempt += 1
        try:
            exec_result = execute_sql(final_sql)
        except http_requests.HTTPError as e:
            exec_result = {"error": {"message": str(e)}}

        error_info = exec_result.get("error")
        if error_info is None:
            break

        error_msg = error_info.get("message", str(error_info))

        if attempt >= MAX_REPAIR_ATTEMPTS:
            break

        classify_result = error_classifier_handler({
            **gen_result,
            "error_message": error_msg,
            "schema": schema,
        })
        error_type = classify_result.get("error_type", "unknown_error")

        repair_result = repair_agent_handler({
            "generated_sql": final_sql,
            "error_message": error_msg,
            "error_type": error_type,
            "schema": schema,
        })
        if repair_result.get("error"):
            break
        final_sql = repair_result["generated_sql"]

    pipeline_ms = int((time.time() - pipeline_start) * 1000)

    # Step 5: Save to history
    if exec_result and exec_result.get("error") is None:
        save_to_history(question, final_sql, exec_result.get("executionTimeMs", 0))

    return QueryResponse(
        question=question,
        sql=final_sql,
        explanation=explanation,
        confidence=explanation.get("confidence", 0),
        attempts=attempt,
        execution_result=exec_result,
        pipeline_time_ms=pipeline_ms,
    )


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
