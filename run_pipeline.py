#!/usr/bin/env python3
"""
Local end-to-end orchestration script for the Text2SQL pipeline.

Replicates what AWS Step Functions does in production, tying together:
  - Spring Boot platform (schema, executor, history)
  - Python AI agents (router, intent planner, SQL generator, verifier, scorer, repair)

Prerequisites:
  1. Postgres running:       cd db && docker compose up -d
  2. Spring Boot running:    cd platform && ./mvnw spring-boot:run
  3. Python deps installed:  cd text2sql-agents && pip install -r requirements.txt
  4. OpenAI key exported:    export OPENAI_API_KEY=sk-...

Usage:
  python run_pipeline.py "What is our total revenue?"
  python run_pipeline.py "Show me total sales by country"
  python run_pipeline.py --interactive
"""

import argparse
import json
import sys
import os
import time

import requests

# Allow imports from text2sql-agents/
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "text2sql-agents"))

from dotenv import load_dotenv
load_dotenv()

PLATFORM_BASE_URL = os.getenv("PLATFORM_URL", "http://localhost:8080")
MAX_REPAIR_ATTEMPTS = 3


# ---------------------------------------------------------------------------
# Platform REST helpers
# ---------------------------------------------------------------------------

def fetch_schema() -> dict:
    """GET /api/schema -> convert to agent-compatible DatabaseSchema dict."""
    resp = requests.get(f"{PLATFORM_BASE_URL}/api/schema", timeout=10)
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

        # Enrich FK info from the snapshot
        for fk in t.get("foreignKeys", []):
            for col in columns:
                if col["name"] == fk.get("columnName"):
                    col["foreign_key"] = f"{fk.get('referencedTable')}.{fk.get('referencedColumn')}"

        tables.append({"name": t["name"], "columns": columns})

    return {"tables": tables}


def execute_sql(sql: str) -> dict:
    """POST /api/executor/execute -> execution result."""
    resp = requests.post(
        f"{PLATFORM_BASE_URL}/api/executor/execute",
        json={"sql": sql},
        timeout=15,
    )
    resp.raise_for_status()
    return resp.json()


def save_to_history(question: str, sql: str, time_ms: int) -> None:
    """POST /api/history -> save successful query."""
    try:
        requests.post(
            f"{PLATFORM_BASE_URL}/api/history",
            json={
                "natural_language_query": question,
                "generated_sql": sql,
                "execution_time_ms": time_ms,
            },
            timeout=5,
        )
    except Exception:
        pass  # non-critical


# ---------------------------------------------------------------------------
# Pipeline
# ---------------------------------------------------------------------------

def run_pipeline(question: str) -> dict:
    """Run the full Text2SQL pipeline for a single question."""
    from agents.lambda_adapter import (
        router_handler,
        intent_planner_handler,
        sql_generator_handler_with_explanation,
        error_classifier_handler,
        repair_agent_handler,
    )

    pipeline_start = time.time()
    print(f"\n{'='*70}")
    print(f"  QUESTION: {question}")
    print(f"{'='*70}")

    # --- Step 0: Fetch schema from Spring Boot ---
    print("\n[Step 0] Fetching schema from platform...")
    schema = fetch_schema()
    table_names = [t["name"] for t in schema["tables"]]
    print(f"  Tables: {', '.join(table_names)}")

    # --- Step 1: Router ---
    print("\n[Step 1] Router -- classifying complexity...")
    router_result = router_handler({"user_query": question})
    if router_result.get("error"):
        print(f"  ERROR: {router_result['message']}")
        return router_result
    decision = router_result["router_decision"]
    print(f"  Decision: {decision}")
    print(f"  Reasoning: {router_result.get('_internal', {}).get('reasoning', '')}")

    # --- Step 2: Intent Planner ---
    print("\n[Step 2] Intent Planner -- extracting structured intent...")
    intent_result = intent_planner_handler({
        "user_query": question,
        "router_decision": decision,
        "schema": schema,
    })
    if intent_result.get("error"):
        print(f"  ERROR: {intent_result['message']}")
        return intent_result
    intent = intent_result["intent"]
    print(f"  Target: {intent.get('target')}")
    print(f"  Aggregation: {intent.get('metric')}")
    print(f"  Tables: {intent.get('tables')}")
    print(f"  Filters: {len(intent.get('filters', []))} filter(s)")

    # --- Step 3: SQL Generator + Verification + Explanation ---
    print(f"\n[Step 3] SQL Generator ({decision})...")
    gen_result = sql_generator_handler_with_explanation({
        "user_query": question,
        "router_decision": decision,
        "intent": intent,
        "schema": schema,
    })
    if gen_result.get("error"):
        print(f"  ERROR: {gen_result['message']}")
        return gen_result
    generated_sql = gen_result["generated_sql"]
    explanation = gen_result.get("explanation", {})
    print(f"  Generated SQL:\n    {generated_sql}")
    print(f"  Explanation: {explanation.get('explanation', '')}")

    # --- Step 4: Execute SQL via Spring Boot ---
    print("\n[Step 4] Executing SQL via platform...")
    attempt = 0
    exec_result = None
    final_sql = generated_sql

    while attempt < MAX_REPAIR_ATTEMPTS:
        attempt += 1
        try:
            exec_result = execute_sql(final_sql)
        except requests.HTTPError as e:
            exec_result = {"error": {"message": str(e)}}

        error_info = exec_result.get("error")
        if error_info is None:
            print(f"  Attempt {attempt}: SUCCESS ({exec_result.get('rowCount', 0)} rows, "
                  f"{exec_result.get('executionTimeMs', 0)}ms)")
            break

        error_msg = error_info.get("message", str(error_info))
        print(f"  Attempt {attempt}: FAILED -- {error_msg}")

        if attempt >= MAX_REPAIR_ATTEMPTS:
            print(f"  Max repair attempts ({MAX_REPAIR_ATTEMPTS}) reached.")
            break

        # --- Step 4a: Classify error ---
        print(f"\n  [Repair] Classifying error...")
        classify_result = error_classifier_handler({
            **gen_result,
            "error_message": error_msg,
            "schema": schema,
        })
        error_type = classify_result.get("error_type", "unknown_error")
        print(f"  Error type: {error_type}")

        # --- Step 4b: Repair SQL ---
        print(f"  [Repair] Attempting fix...")
        repair_result = repair_agent_handler({
            "generated_sql": final_sql,
            "error_message": error_msg,
            "error_type": error_type,
            "schema": schema,
        })
        if repair_result.get("error"):
            print(f"  Repair failed: {repair_result['message']}")
            break
        final_sql = repair_result["generated_sql"]
        print(f"  Repaired SQL:\n    {final_sql}")

    pipeline_ms = int((time.time() - pipeline_start) * 1000)

    # --- Step 5: Save to history ---
    if exec_result and exec_result.get("error") is None:
        save_to_history(question, final_sql, exec_result.get("executionTimeMs", 0))

    # --- Summary ---
    print(f"\n{'='*70}")
    print("  PIPELINE SUMMARY")
    print(f"{'='*70}")
    print(f"  Question:    {question}")
    print(f"  Strategy:    {decision}")
    print(f"  Final SQL:   {final_sql}")
    print(f"  Explanation: {explanation.get('explanation', 'N/A')}")
    print(f"  Attempts:    {attempt}")
    print(f"  Total time:  {pipeline_ms}ms")

    if exec_result and exec_result.get("error") is None:
        print(f"  Rows:        {exec_result.get('rowCount', 0)}")
        print(f"  Columns:     {', '.join(exec_result.get('columns', []))}")
        rows = exec_result.get("rows", [])
        if rows:
            print(f"\n  Results (first {min(5, len(rows))} rows):")
            for row in rows[:5]:
                print(f"    {row}")
            if len(rows) > 5:
                print(f"    ... and {len(rows) - 5} more rows")
    else:
        print(f"  Status:      FAILED")
        if exec_result:
            print(f"  Last error:  {exec_result.get('error', {}).get('message', 'Unknown')}")

    confidence = explanation.get("confidence", 0)
    return {
        "question": question,
        "sql": final_sql,
        "explanation": explanation,
        "confidence": confidence,
        "attempts": attempt,
        "execution_result": exec_result,
        "pipeline_time_ms": pipeline_ms,
    }


# ---------------------------------------------------------------------------
# CLI entry point
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description="Run the Text2SQL pipeline locally (agents + Spring Boot platform)"
    )
    parser.add_argument(
        "question",
        nargs="?",
        help="Natural language question to convert to SQL",
    )
    parser.add_argument(
        "--interactive", "-i",
        action="store_true",
        help="Interactive mode: keep asking questions",
    )
    parser.add_argument(
        "--platform-url",
        default=None,
        help="Spring Boot base URL (default: http://localhost:8080)",
    )

    args = parser.parse_args()

    if args.platform_url:
        global PLATFORM_BASE_URL
        PLATFORM_BASE_URL = args.platform_url

    # Verify platform is reachable
    try:
        resp = requests.get(f"{PLATFORM_BASE_URL}/actuator/health", timeout=5)
        if resp.status_code != 200:
            print(f"WARNING: Platform health check returned {resp.status_code}")
    except requests.ConnectionError:
        print(f"ERROR: Cannot reach platform at {PLATFORM_BASE_URL}")
        print("Make sure Spring Boot is running: cd platform && ./mvnw spring-boot:run")
        sys.exit(1)

    if not os.getenv("OPENAI_API_KEY"):
        print("ERROR: OPENAI_API_KEY environment variable is not set")
        sys.exit(1)

    if args.interactive:
        print("Text2SQL Interactive Mode (type 'quit' to exit)")
        print("-" * 50)
        while True:
            try:
                question = input("\nQuestion: ").strip()
            except (EOFError, KeyboardInterrupt):
                print("\nGoodbye!")
                break
            if not question or question.lower() in ("quit", "exit", "q"):
                break
            run_pipeline(question)
    elif args.question:
        result = run_pipeline(args.question)
        print(f"\n--- JSON Output ---")
        print(json.dumps(result, indent=2, default=str))
    else:
        parser.print_help()
        sys.exit(1)


if __name__ == "__main__":
    main()
