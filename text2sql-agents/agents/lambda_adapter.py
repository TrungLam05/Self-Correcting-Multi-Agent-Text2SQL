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

from typing import Any, Dict, Optional
from shared.contracts import (
    RouterOutput, 
    QueryIntent, 
    SQLOutput,
    DatabaseSchema,
    ExplanationOutput
)
from agents.router import classify_complexity
from agents.intent_planner import extract_intent


def parse_lambda_input(event: Dict[str, Any]) -> Dict[str, Any]:
    """
    Parse incoming Lambda event into internal format.
    
    Args:
        event: Raw Lambda event from API Gateway/Step Functions
    
    Returns:
        Parsed input with user_query and optional schema
    """
    return {
        "user_query": event.get("user_query", ""),
        "schema": event.get("schema"),  # May be None
        # Pass through any existing pipeline state
        "router_decision": event.get("router_decision"),
        "intent": event.get("intent"),
        "generated_sql": event.get("generated_sql"),
    }


def convert_router_output(router_output: RouterOutput) -> str:
    """
    Convert internal RouterOutput to expected format.
    
    Internal: {"complexity": "simple", "strategy": "single_candidate"}
    External: "standard_strategy" or "multi_candidate_strategy"
    """
    if router_output.complexity == "simple":
        return "standard_strategy"
    else:
        return "multi_candidate_strategy"


def convert_intent_output(intent: QueryIntent) -> Dict[str, Any]:
    """
    Convert internal QueryIntent to Developer C's expected format.
    
    Internal: {"metric": "total_amount", "aggregation": "SUM", ...}
    External: {"target": "total_amount", "metric": "sum", ...}
    """
    return {
        "target": intent.metric,
        "metric": intent.aggregation.lower() if intent.aggregation else None,
        "filters": [
            {
                "column": f.column,
                "operator": f.operator,
                "value": f.value
            }
            for f in intent.filters
        ],
        "group_by": intent.group_by,
        "order_by": {
            "column": intent.order_by.column,
            "direction": intent.order_by.direction
        } if intent.order_by else None,
        "limit": intent.limit,
        "tables": intent.tables
    }


def convert_sql_output(sql_output: SQLOutput) -> str:
    """
    Convert internal SQLOutput to Developer C's expected format.
    
    Internal: {"sql_query": "SELECT...", "confidence": 0.9, ...}
    External: Just the SQL string
    """
    return sql_output.sql_query

def convert_explanation_output(explanation: ExplanationOutput) -> Dict[str, Any]:
    return {
        "explanation": explanation.explanation,
        "key_operations": explanation.key_operations,
        "tables_accessed": explanation.tables_accessed,
        "confidence": explanation.confidence
    }
# =============================================================================
# Lambda Handler Functions
# =============================================================================

def router_handler(event: Dict[str, Any], context: Any = None) -> Dict[str, Any]:
    """
    Lambda handler for Router Agent.
    
    Input:
        {"user_query": "Show me total sales", ...}
    
    Output:
        {"user_query": "...", "router_decision": "standard_strategy", ...}
    """
    try:
        parsed = parse_lambda_input(event)
        user_query = parsed["user_query"]
        
        # Run your router logic
        router_output = classify_complexity(user_query)
        
        # Convert to expected format and pass through pipeline state
        return {
            "user_query": user_query,
            "router_decision": convert_router_output(router_output),
            # Include internal details for logging/debugging
            "_internal": {
                "complexity": router_output.complexity,
                "strategy": router_output.strategy,
                "reasoning": router_output.reasoning
            }
        }
    
    except Exception as e:
        return {
            "error": True,
            "error_type": "router_error",
            "message": str(e)
        }


def intent_planner_handler(event: Dict[str, Any], context: Any = None) -> Dict[str, Any]:
    """
    Lambda handler for Intent Planner Agent.
    
    Input:
        {
            "user_query": "Show me total sales",
            "router_decision": "standard_strategy",
            "schema": {...}  # From Schema Tool
        }
    
    Output:
        {
            "user_query": "...",
            "router_decision": "...",
            "intent": {"target": "...", "metric": "..."}
        }
    """
    try:
        parsed = parse_lambda_input(event)
        user_query = parsed["user_query"]
        schema_dict = parsed.get("schema")
        
        # Parse schema if provided
        if schema_dict:
            schema = DatabaseSchema(**schema_dict)
        else:
            raise ValueError("Schema is required for intent planning")
        
        # Run your intent planner logic
        intent = extract_intent(user_query, schema)
        
        # Convert to expected format and pass through pipeline state
        return {
            "user_query": user_query,
            "router_decision": parsed.get("router_decision"),
            "intent": convert_intent_output(intent),
            # Include internal details for logging/debugging
            "_internal_intent": intent.model_dump()
        }
    
    except Exception as e:
        return {
            "error": True,
            "error_type": "intent_planner_error",
            "message": str(e)
        }


# def sql_generator_handler(event: Dict[str, Any], context: Any = None) -> Dict[str, Any]:
#     """
#     Lambda handler for SQL Generator Agent.
    
#     Input:
#         {
#             "user_query": "...",
#             "router_decision": "...",
#             "intent": {...},
#             "schema": {...}
#         }
    
#     Output:
#         {
#             "user_query": "...",
#             "router_decision": "...",
#             "intent": {...},
#             "generated_sql": "SELECT..."
#         }
#     """
#     try:
#         parsed = parse_lambda_input(event)
        
#         # You'll implement this when you build sql_generator.py
#         # For now, placeholder:
#         from agents.sql_generator import generate_sql
        
#         intent_dict = parsed.get("intent")
#         schema_dict = parsed.get("schema")
        
#         if not intent_dict or not schema_dict:
#             raise ValueError("Intent and schema are required for SQL generation")
        
#         # Convert expected intent format back to internal format
#         internal_intent = QueryIntent(
#             metric=intent_dict.get("target"),
#             aggregation=intent_dict.get("metric", "").upper() if intent_dict.get("metric") else None,
#             filters=intent_dict.get("filters", []),
#             group_by=intent_dict.get("group_by", []),
#             order_by=intent_dict.get("order_by"),
#             limit=intent_dict.get("limit"),
#             tables=intent_dict.get("tables", [])
#         )
        
#         schema = DatabaseSchema(**schema_dict)
        
#         # Run your SQL generator logic
#         sql_output = generate_sql(internal_intent, schema)
        
#         # Return in expected format
#         return {
#             "user_query": parsed.get("user_query"),
#             "router_decision": parsed.get("router_decision"),
#             "intent": intent_dict,  # Pass through as-is
#             "generated_sql": convert_sql_output(sql_output),
#             "_internal_sql": sql_output.model_dump()
#         }
    
#     except Exception as e:
#         return {
#             "error": True,
#             "error_type": "sql_generator_error",
#             "message": str(e)
#         }


#Generate SQL + Explanation together (efficient, 1 LLM call)
def sql_generator_handler_with_explanation(event: Dict[str, Any], context: Any = None) -> Dict[str, Any]:
    """
    Generate SQL and explanation in one call.
    Use this for most cases - it's more efficient.
    """
    try:
        parsed = parse_lambda_input(event)
        
        from agents.sql_generator import generate_sql_with_explanation
        
        intent_dict = parsed.get("intent")
        schema_dict = parsed.get("schema")
        user_query = parsed.get("user_query")
        
        if not intent_dict or not schema_dict or not user_query:
            raise ValueError("Intent, schema, and user_query are required")
        
        # Convert to internal format
        internal_intent = QueryIntent(
            metric=intent_dict.get("target"),
            aggregation=intent_dict.get("metric", "").upper() if intent_dict.get("metric") else None,
            filters=intent_dict.get("filters", []),
            group_by=intent_dict.get("group_by", []),
            order_by=intent_dict.get("order_by"),
            limit=intent_dict.get("limit"),
            tables=intent_dict.get("tables", [])
        )
        
        schema = DatabaseSchema(**schema_dict)
        
        # Generate both in one call
        sql_output, explanation_output = generate_sql_with_explanation(
            internal_intent, schema, user_query
        )
        
        return {
            "user_query": user_query,
            "router_decision": parsed.get("router_decision"),
            "intent": intent_dict,
            "generated_sql": convert_sql_output(sql_output),
            "explanation": convert_explanation_output(explanation_output),
            "_internal_sql": sql_output.model_dump(),
            "_internal_explanation": explanation_output.model_dump()
        }
    
    except Exception as e:
        return {"error": True, "error_type": "sql_generator_error", "message": str(e)}
