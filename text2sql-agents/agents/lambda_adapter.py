"""
Adapter layer to convert between internal models and Lambda JSON format.

Expected format:
{
    "user_query": "Show me total sales",
    "router_decision": "standard_strategy",
    "intent": {
        "target": "sales",
        "metric": "sum"
    },
    "generated_sql": "SELECT...",
    "execution_result": [...]
}
"""

from typing import Any, Dict
from shared.contracts import (
    RouterOutput,
    QueryIntent,
    SQLOutput,
    DatabaseSchema,
    ExplanationOutput,
    ErrorType,
    RepairInput,
)
from agents.router import classify_complexity
from agents.intent_planner import extract_intent


def parse_lambda_input(event: Dict[str, Any]) -> Dict[str, Any]:
    """Parse incoming Lambda event into internal format."""
    return {
        "user_query": event.get("user_query", ""),
        "schema": event.get("schema"),
        "router_decision": event.get("router_decision"),
        "intent": event.get("intent"),
        "generated_sql": event.get("generated_sql"),
        "error_message": event.get("error_message"),
    }


def convert_router_output(router_output: RouterOutput) -> str:
    if router_output.complexity == "simple":
        return "standard_strategy"
    return "multi_candidate_strategy"


def convert_intent_output(intent: QueryIntent) -> Dict[str, Any]:
    return {
        "target": intent.metric,
        "metric": intent.aggregation.lower() if intent.aggregation else None,
        "filters": [
            {"column": f.column, "operator": f.operator, "value": f.value}
            for f in intent.filters
        ],
        "group_by": intent.group_by,
        "order_by": {
            "column": intent.order_by.column,
            "direction": intent.order_by.direction,
        } if intent.order_by else None,
        "limit": intent.limit,
        "tables": intent.tables,
    }


def convert_sql_output(sql_output: SQLOutput) -> str:
    return sql_output.sql_query


def convert_explanation_output(explanation: ExplanationOutput) -> Dict[str, Any]:
    return {
        "explanation": explanation.explanation,
        "key_operations": explanation.key_operations,
        "tables_accessed": explanation.tables_accessed,
        "confidence": explanation.confidence,
    }


# =============================================================================
# Lambda Handler Functions
# =============================================================================

def router_handler(event: Dict[str, Any], context: Any = None) -> Dict[str, Any]:
    """
    Lambda handler for Router Agent.

    Input:  {"user_query": "Show me total sales", ...}
    Output: {"user_query": "...", "router_decision": "standard_strategy", ...}
    """
    try:
        parsed = parse_lambda_input(event)
        user_query = parsed["user_query"]

        router_output = classify_complexity(user_query)

        return {
            "user_query": user_query,
            "router_decision": convert_router_output(router_output),
            "_internal": {
                "complexity": router_output.complexity,
                "strategy": router_output.strategy,
                "reasoning": router_output.reasoning,
            },
        }
    except Exception as e:
        return {"error": True, "error_type": "router_error", "message": str(e)}


def intent_planner_handler(event: Dict[str, Any], context: Any = None) -> Dict[str, Any]:
    """
    Lambda handler for Intent Planner Agent.

    Input:  {"user_query": "...", "router_decision": "...", "schema": {...}}
    Output: {"user_query": "...", "router_decision": "...", "intent": {...}}
    """
    try:
        parsed = parse_lambda_input(event)
        user_query = parsed["user_query"]
        schema_dict = parsed.get("schema")

        if schema_dict:
            schema = DatabaseSchema(**schema_dict)
        else:
            raise ValueError("Schema is required for intent planning")

        intent = extract_intent(user_query, schema)

        return {
            "user_query": user_query,
            "router_decision": parsed.get("router_decision"),
            "intent": convert_intent_output(intent),
            "_internal_intent": intent.model_dump(),
        }
    except Exception as e:
        return {"error": True, "error_type": "intent_planner_error", "message": str(e)}


def sql_generator_handler_with_explanation(event: Dict[str, Any], context: Any = None) -> Dict[str, Any]:
    """
    Lambda handler for SQL Generator + Explanation.
    Handles both single-candidate and multi-candidate strategies.
    """
    try:
        parsed = parse_lambda_input(event)

        from agents.sql_generator import generate_candidates, generate_sql, explain_generated_sql
        from agents.sql_verifier import verify_sql_against_intent
        from agents.semantic_scorer import pick_best, score_candidates

        intent_dict = parsed.get("intent")
        schema_dict = parsed.get("schema")
        user_query = parsed.get("user_query")

        if not intent_dict or not schema_dict or not user_query:
            raise ValueError("Intent, schema, and user_query are required")

        internal_intent = QueryIntent(
            metric=intent_dict.get("target"),
            aggregation=intent_dict.get("metric", "").upper() if intent_dict.get("metric") else None,
            filters=intent_dict.get("filters", []),
            group_by=intent_dict.get("group_by", []),
            order_by=intent_dict.get("order_by"),
            limit=intent_dict.get("limit"),
            tables=intent_dict.get("tables", []),
        )

        schema = DatabaseSchema(**schema_dict)
        router_decision = parsed.get("router_decision")
        internal_verification_dump: Any = None

        if router_decision == "multi_candidate_strategy":
            candidate_output = generate_candidates(internal_intent, schema, n=3)
            if not candidate_output.candidates:
                raise ValueError("No safe SQL candidates were generated")

            verified_candidates: list[tuple[SQLOutput, Any]] = []
            all_verifications: list[dict[str, Any]] = []

            for candidate in candidate_output.candidates:
                v = verify_sql_against_intent(candidate.sql_query, internal_intent)
                vd = v.model_dump()
                all_verifications.append({
                    "sql": candidate.sql_query,
                    "confidence": candidate.confidence,
                    "verification": vd,
                })
                if v.ok:
                    verified_candidates.append((candidate, vd))

            internal_verification_dump = {
                "mode": "multi_candidate",
                "all": all_verifications,
                "passing_count": len(verified_candidates),
                "total_count": len(candidate_output.candidates),
            }

            if not verified_candidates:
                return {
                    "error": True,
                    "error_type": "sql_verification_error",
                    "message": "All SQL candidates failed A7 verification",
                    "_internal_verification": internal_verification_dump,
                }

            passing_sql_outputs = [c for c, _vd in verified_candidates]
            scored = score_candidates(
                user_question=user_query,
                intent=internal_intent,
                schema=schema,
                candidates=passing_sql_outputs,
            )
            best = pick_best(scored)

            sql_output = best.candidate
            selected_v = verify_sql_against_intent(sql_output.sql_query, internal_intent)

            internal_verification_dump["selected"] = {
                "sql": sql_output.sql_query,
                "confidence": sql_output.confidence,
                "verification": selected_v.model_dump(),
            }
            internal_verification_dump["semantic_scoring"] = {
                "model": "gpt-5-mini",
                "scores": [
                    {
                        "sql": s.candidate.sql_query,
                        "confidence": s.candidate.confidence,
                        "score": s.score,
                        "rationale": s.rationale,
                    }
                    for s in scored
                ],
                "selected": {
                    "sql": sql_output.sql_query,
                    "score": best.score,
                    "rationale": best.rationale,
                },
            }
            internal_sql_dump: Any = {
                "selected": sql_output.model_dump(),
                "candidates": [c.model_dump() for c in candidate_output.candidates],
            }
        else:
            sql_output = generate_sql(internal_intent, schema)
            v = verify_sql_against_intent(sql_output.sql_query, internal_intent)
            internal_verification_dump = {
                "mode": "single_candidate",
                "verification": v.model_dump(),
            }
            if not v.ok:
                return {
                    "error": True,
                    "error_type": "sql_verification_error",
                    "message": "Generated SQL failed A7 verification",
                    "_internal_verification": internal_verification_dump,
                    "generated_sql": convert_sql_output(sql_output),
                }
            internal_sql_dump = sql_output.model_dump()

        explanation_output = explain_generated_sql(
            sql_output.sql_query, user_query, schema
        )

        return {
            "user_query": user_query,
            "router_decision": router_decision,
            "intent": intent_dict,
            "generated_sql": convert_sql_output(sql_output),
            "explanation": convert_explanation_output(explanation_output),
            "_internal_sql": internal_sql_dump,
            "_internal_verification": internal_verification_dump,
            "_internal_explanation": explanation_output.model_dump(),
        }

    except Exception as e:
        return {"error": True, "error_type": "sql_generator_error", "message": str(e)}


def executor_handler(event: Dict[str, Any], context: Any = None) -> Dict[str, Any]:
    """
    Lambda handler that proxies SQL execution to the Spring Boot platform.
    Requires PLATFORM_URL environment variable.

    Input:  {"generated_sql": "SELECT ...", ...}
    Output: {"execution_result": {"status": "SUCCEEDED"|"FAILED", ...}, ...}
    """
    try:
        import os
        import httpx

        platform_url = os.environ.get("PLATFORM_URL", "http://localhost:8080")
        sql = event.get("generated_sql", "")
        if not sql:
            raise ValueError("generated_sql is required")

        resp = httpx.post(
            f"{platform_url}/api/executor/execute",
            json={"sql": sql},
            timeout=10.0,
        )
        result = resp.json()

        if result.get("error"):
            return {
                **event,
                "execution_result": {
                    "status": "FAILED",
                    "error_message": result["error"].get("message", "Unknown error"),
                    "error_code": result["error"].get("code", "UNKNOWN"),
                },
            }

        return {
            **event,
            "execution_result": {
                "status": "SUCCEEDED",
                "rows": result.get("rows", []),
                "columns": result.get("columns", []),
                "rowCount": result.get("rowCount", 0),
                "executionTimeMs": result.get("executionTimeMs", 0),
            },
        }
    except Exception as e:
        return {
            **event,
            "execution_result": {
                "status": "FAILED",
                "error_message": str(e),
                "error_code": "LAMBDA_ERROR",
            },
        }


def error_classifier_handler(event: Dict[str, Any], context: Any = None) -> Dict[str, Any]:
    """
    Lambda handler for Error Classifier Agent (A4).

    Input:  {"error_message": "column \"foo\" does not exist", ...}
    Output: {"error_type": "schema_error", ...}
    """
    try:
        from agents.error_classifier import classify_error

        parsed = parse_lambda_input(event)
        error_message = parsed.get("error_message", "")
        if not error_message:
            raise ValueError("error_message is required")

        error_type = classify_error(error_message)

        return {
            **event,
            "error_type": error_type.value,
        }
    except Exception as e:
        return {"error": True, "error_type": "classifier_error", "message": str(e)}


def repair_agent_handler(event: Dict[str, Any], context: Any = None) -> Dict[str, Any]:
    """
    Lambda handler for Repair Agent (A5).

    Input:  {"generated_sql": "SELECT ...", "error_message": "...", "error_type": "schema_error", "schema": {...}}
    Output: {"generated_sql": "<fixed SQL>", "repair_metadata": {...}, ...}
    """
    try:
        from agents.repair_agent import repair_sql

        parsed = parse_lambda_input(event)
        bad_sql = parsed.get("generated_sql", "")
        error_message = event.get("error_message", "")
        error_type_str = event.get("error_type", "unknown_error")
        schema_dict = parsed.get("schema")

        if not bad_sql:
            raise ValueError("generated_sql is required for repair")
        if not schema_dict:
            raise ValueError("schema is required for repair")

        error_type = ErrorType(error_type_str)
        schema = DatabaseSchema(**schema_dict)

        repair_input = RepairInput(
            bad_sql=bad_sql,
            error_message=error_message,
            error_type=error_type,
        )

        repair_output = repair_sql(repair_input, schema)

        return {
            **event,
            "generated_sql": repair_output.fixed_sql,
            "repair_metadata": {
                "original_sql": bad_sql,
                "fixed_sql": repair_output.fixed_sql,
                "reasoning": repair_output.reasoning,
                "confidence": repair_output.confidence,
            },
        }
    except Exception as e:
        return {"error": True, "error_type": "repair_error", "message": str(e)}
