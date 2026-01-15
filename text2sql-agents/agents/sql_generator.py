"""SQL Generator Agent: Convert intent to SQL using LLM."""

import json
import os
import re
from openai import OpenAI
from shared.contracts import DatabaseSchema, ExplanationOutput, QueryIntent, SQLOutput
from agents.inference import chat_completion_text
from dotenv import load_dotenv
load_dotenv()
    

SYSTEM_PROMPT = """You are a SQL generator for PostgreSQL.

Generate ONLY SELECT statements. Never use INSERT, UPDATE, DELETE, DROP, or any DDL.
Do NOT use WITH/CTEs. The query MUST start with SELECT.
Return EXACTLY ONE SQL query. End it with a single semicolon.

Rules:
1. Use explicit column names (no SELECT *)
2. Use table aliases (e.g., o for orders, c for customers)
3. Add column aliases for aggregations (e.g., SUM(amount) AS total_amount)
4. Format SQL cleanly with proper indentation
5. Always validate column names against the provided schema

Schema:
{schema}

Return ONLY the SQL query. No explanations, no markdown code blocks, just raw SQL.
"""

# NEW: Combined prompt for SQL + Explanation
SYSTEM_PROMPT_WITH_EXPLANATION = """You are a SQL generator for PostgreSQL.

Generate ONLY SELECT statements. Never use INSERT, UPDATE, DELETE, DROP, or any DDL.

Rules:
1. Use explicit column names (no SELECT *)
2. Use table aliases (e.g., o for orders, c for customers)
3. Add column aliases for aggregations (e.g., SUM(amount) AS total_amount)
4. Format SQL cleanly with proper indentation
5. Always validate column names against the provided schema

Schema:
{schema}

Return a JSON object with:
{{
    "sql": "The SQL query",
    "explanation": "Clear 2-3 sentence explanation of what the query does",
    "key_operations": ["Operation 1", "Operation 2"],
    "confidence": 0.9
}}
"""

def format_schema(schema: DatabaseSchema) -> str:
    """Format the database schema into a string for the LLM."""
    lines = []
    for table in schema.tables:
        lines.append(f"Table: {table.name}")
        for column in table.columns:
            col_info = f"  - {column.name} ({column.type})"
            if column.primary_key:
                col_info += " [PRIMARY KEY]"
            if column.foreign_key:
                col_info += f" [FK -> {column.foreign_key}]"
            lines.append(col_info)
        lines.append("")
    return "\n".join(lines)


def clean_sql(sql: str) -> str:
    """Remove markdown formatting from LLM output."""
    # Remove ```sql ... ``` wrapper
    sql = re.sub(r'^```(?:sql)?\n?', '', sql, flags=re.MULTILINE)
    sql = re.sub(r'\n?```$', '', sql, flags=re.MULTILINE)

    # If the model emits any preamble, keep only from the first SELECT.
    # This supports strict validation that the statement starts with SELECT.
    s = sql.strip()
    m = re.search(r'\bSELECT\b', s, flags=re.IGNORECASE)
    if m and m.start() > 0:
        s = s[m.start():].lstrip()

    # If the model appended commentary after a semicolon, strip it.
    # IMPORTANT: do NOT strip if a *second SQL statement* clearly starts right after the semicolon;
    # in that case we want safety validation to reject it.
    if ";" in s:
        first, _sep, rest = s.partition(";")
        if rest.strip():
            rest_lstrip = rest.lstrip()
            starts_like_stmt = re.match(
                r"^(SELECT|WITH|INSERT|UPDATE|DELETE|CREATE|DROP|ALTER|TRUNCATE|GRANT|REVOKE)\b",
                rest_lstrip,
                flags=re.IGNORECASE,
            )
            if not starts_like_stmt:
                s = first.strip() + ";"

    # Normalize: ensure a single trailing semicolon.
    if s and not s.rstrip().endswith(";"):
        s = s.rstrip() + ";"

    return s


def validate_sql_safety(sql: str) -> None:
    """
    Validate that SQL is safe to execute.
    
    Raises:
        ValueError: If SQL contains forbidden operations
    """
    sql_upper = sql.upper().strip()
    
    # Must start with SELECT
    if not sql_upper.startswith('SELECT'):
        raise ValueError("SQL must be a SELECT statement")

    # If the model accidentally returned multiple top-level SELECT blocks without semicolons,
    # reject it explicitly. (We only check for SELECT at column 0 to avoid false positives
    # from indented subqueries.)
    if ";" not in sql_upper:
        top_level_selects = re.findall(r"(?m)^SELECT\b", sql_upper)
        if len(top_level_selects) > 1:
            raise ValueError("Multiple SQL statements not allowed")
    
    # Check for forbidden keywords
    forbidden = [
        'INSERT', 'UPDATE', 'DELETE', 'DROP', 'CREATE', 'ALTER', 
        'TRUNCATE', 'GRANT', 'REVOKE', 'EXEC', 'EXECUTE'
    ]
    
    for keyword in forbidden:
        # Use word boundary to avoid false positives
        if re.search(rf'\b{keyword}\b', sql_upper):
            raise ValueError(f"Forbidden SQL operation: {keyword}")
    
    # Check for multiple statements
    if re.search(r';\s*\w', sql):
        raise ValueError("Multiple SQL statements not allowed")


def generate_sql(intent: QueryIntent, schema: DatabaseSchema) -> SQLOutput:
    """
    Generate SQL from structured intent using OpenAI.
    
    Args:
        intent: Structured query intent from Intent Planner
        schema: Database schema for context
    
    Returns:
        SQLOutput with generated SQL and metadata
    
    Raises:
        ValueError: If inputs are invalid or SQL is unsafe
        RuntimeError: If OpenAI API call fails
    """
    # Validate inputs
    if not intent:
        raise ValueError("Intent is required")
    
    if not schema or not schema.tables:
        raise ValueError("Schema with tables is required")
    
    formatted_schema = format_schema(schema)
    intent_json = json.dumps(intent.model_dump(), indent=2)
    
    try:
        raw = chat_completion_text(
            model="gpt-5-mini",
            messages=[
                {"role": "system", "content": SYSTEM_PROMPT.format(schema=formatted_schema)},
                {"role": "user", "content": f"Intent:\n{intent_json}\n\nGenerate exactly ONE SQL query. Output must start with SELECT (no WITH/CTE) and end with a single semicolon."}
            ],
        )[0]
    except Exception as e:
        raise RuntimeError(f"OpenAI API call failed: {str(e)}")
    
    # Extract and clean SQL
    sql = clean_sql(raw)
    
    # Validate safety
    validate_sql_safety(sql)
    
    # Calculate confidence (simple heuristic)
    confidence = 0.9
    if len(intent.tables) > 1:
        confidence -= 0.1  # JOINs are harder
    if len(intent.filters) > 2:
        confidence -= 0.1  # Complex filters
    confidence = max(0.5, confidence)

    sql_output = SQLOutput(
        sql_query=sql,
        confidence=confidence,
        tables_used=intent.tables
    )

    # Sprint 3 - Role A (A7): Verify SQL matches the structured intent
    from agents.sql_verifier import verify_sql_against_intent

    verification = verify_sql_against_intent(sql_output.sql_query, intent)
    if not verification.ok:
        issues = "; ".join(f"{i.kind}: {i.message}" for i in verification.issues)
        raise ValueError(f"Generated SQL failed A7 verification: {issues}")

    return sql_output
# NEW: Generate SQL with explanation in one call
def generate_sql_with_explanation(
    intent: QueryIntent, 
    schema: DatabaseSchema,
    natural_language_query: str
) -> tuple[SQLOutput, ExplanationOutput]:
    """
    Generate SQL and explanation in a single LLM call (more efficient).
    
    Args:
        intent: Structured query intent
        schema: Database schema
        natural_language_query: Original user question for context
    
    Returns:
        Tuple of (SQLOutput, ExplanationOutput)
    """
    if not intent or not schema or not schema.tables:
        raise ValueError("Intent and schema are required")
    
    api_key = os.getenv("OPENAI_API_KEY")
    if not api_key:
        raise ValueError("OPENAI_API_KEY environment variable is not set")
    
    client = OpenAI(api_key=api_key)
    formatted_schema = format_schema(schema)
    intent_json = json.dumps(intent.model_dump(), indent=2)
    
    try:
        response = client.chat.completions.create(
            model="gpt-4o-mini",
            messages=[
                {
                    "role": "system",
                    "content": SYSTEM_PROMPT_WITH_EXPLANATION.format(schema=formatted_schema)
                },
                {
                    "role": "user",
                    "content": f"Original Question: {natural_language_query}\n\nIntent:\n{intent_json}\n\nGenerate SQL with explanation."
                }
            ],
            response_format={"type": "json_object"},
            temperature=0.3
        )
    except Exception as e:
        raise RuntimeError(f"OpenAI API call failed: {str(e)}")
    
    # Parse JSON response
    try:
        result = json.loads(response.choices[0].message.content)
    except json.JSONDecodeError as e:
        raise RuntimeError(f"Failed to parse LLM response: {str(e)}")
    
    sql = result.get("sql", "")
    sql = clean_sql(sql)
    validate_sql_safety(sql)
    
    # Create SQLOutput
    sql_output = SQLOutput(
        sql_query=sql,
        confidence=result.get("confidence", 0.85),
        tables_used=intent.tables
    )
    
    # Create ExplanationOutput
    explanation_output = ExplanationOutput(
        explanation=result.get("explanation", ""),
        key_operations=result.get("key_operations", []),
        tables_accessed=intent.tables,
        confidence=result.get("confidence", 0.85)
    )
    
    return sql_output, explanation_output


# NEW: Add explanation to existing SQL (separate call)
def explain_generated_sql(
    sql_query: str,
    natural_language_query: str,
    schema: DatabaseSchema
) -> ExplanationOutput:
    """
    Generate explanation for already-generated SQL (uses separate LLM call).
    Use this when you already have SQL and just need explanation.
    
    Args:
        sql_query: The generated SQL
        natural_language_query: Original user question
        schema: Database schema
    
    Returns:
        ExplanationOutput
    """
    if not sql_query or not sql_query.strip():
        raise ValueError("SQL query cannot be empty")
    
    if not schema or not schema.tables:
        raise ValueError("Schema is required")
    
    api_key = os.getenv("OPENAI_API_KEY")
    if not api_key:
        raise ValueError("OPENAI_API_KEY environment variable is not set")
    
    client = OpenAI(api_key=api_key)
    formatted_schema = format_schema(schema)
    
    prompt = f"""Explain this SQL query in 2-3 clear sentences.

Original Question: {natural_language_query}

SQL:
{sql_query}

Schema context:
{formatted_schema}

Return JSON:
{{
    "explanation": "Clear explanation",
    "key_operations": ["Op1", "Op2"],
    "confidence": 0.9
}}
"""
    
    try:
        response = client.chat.completions.create(
            model="gpt-4o-mini",
            messages=[
                {
                    "role": "system",
                    "content": "You are a SQL explainer. Convert SQL to business-friendly explanations."
                },
                {"role": "user", "content": prompt}
            ],
            response_format={"type": "json_object"},
            temperature=0.3
        )
    except Exception as e:
        raise RuntimeError(f"OpenAI API call failed: {str(e)}")
    
    try:
        result = json.loads(response.choices[0].message.content)
    except json.JSONDecodeError as e:
        raise RuntimeError(f"Failed to parse explanation: {str(e)}")
    
    # Extract tables from SQL
    tables = []
    for table in schema.tables:
        if table.name.lower() in sql_query.lower():
            tables.append(table.name)
    
    return ExplanationOutput(
        explanation=result.get("explanation", ""),
        key_operations=result.get("key_operations", []),
        tables_accessed=tables,
        confidence=result.get("confidence", 0.85)
    )

# NEW: Generate SQL with explanation in one call
def generate_sql_with_explanation(
    intent: QueryIntent, 
    schema: DatabaseSchema,
    natural_language_query: str
) -> tuple[SQLOutput, ExplanationOutput]:
    """
    Generate SQL and explanation in a single LLM call (more efficient).
    
    Args:
        intent: Structured query intent
        schema: Database schema
        natural_language_query: Original user question for context
    
    Returns:
        Tuple of (SQLOutput, ExplanationOutput)
    """
    if not intent or not schema or not schema.tables:
        raise ValueError("Intent and schema are required")
    
    api_key = os.getenv("OPENAI_API_KEY")
    if not api_key:
        raise ValueError("OPENAI_API_KEY environment variable is not set")
    
    client = OpenAI(api_key=api_key)
    formatted_schema = format_schema(schema)
    intent_json = json.dumps(intent.model_dump(), indent=2)
    
    try:
        response = client.chat.completions.create(
            model="gpt-4o-mini",
            messages=[
                {
                    "role": "system",
                    "content": SYSTEM_PROMPT_WITH_EXPLANATION.format(schema=formatted_schema)
                },
                {
                    "role": "user",
                    "content": f"Original Question: {natural_language_query}\n\nIntent:\n{intent_json}\n\nGenerate SQL with explanation."
                }
            ],
            response_format={"type": "json_object"},
            temperature=0.3
        )
    except Exception as e:
        raise RuntimeError(f"OpenAI API call failed: {str(e)}")
    
    # Parse JSON response
    try:
        result = json.loads(response.choices[0].message.content)
    except json.JSONDecodeError as e:
        raise RuntimeError(f"Failed to parse LLM response: {str(e)}")
    
    sql = result.get("sql", "")
    sql = clean_sql(sql)
    validate_sql_safety(sql)
    
    # Create SQLOutput
    sql_output = SQLOutput(
        sql_query=sql,
        confidence=result.get("confidence", 0.85),
        tables_used=intent.tables
    )
    
    # Create ExplanationOutput
    explanation_output = ExplanationOutput(
        explanation=result.get("explanation", ""),
        key_operations=result.get("key_operations", []),
        tables_accessed=intent.tables,
        confidence=result.get("confidence", 0.85)
    )
    
    return sql_output, explanation_output


# NEW: Add explanation to existing SQL (separate call)
def explain_generated_sql(
    sql_query: str,
    natural_language_query: str,
    schema: DatabaseSchema
) -> ExplanationOutput:
    """
    Generate explanation for already-generated SQL (uses separate LLM call).
    Use this when you already have SQL and just need explanation.
    
    Args:
        sql_query: The generated SQL
        natural_language_query: Original user question
        schema: Database schema
    
    Returns:
        ExplanationOutput
    """
    if not sql_query or not sql_query.strip():
        raise ValueError("SQL query cannot be empty")
    
    if not schema or not schema.tables:
        raise ValueError("Schema is required")
    
    api_key = os.getenv("OPENAI_API_KEY")
    if not api_key:
        raise ValueError("OPENAI_API_KEY environment variable is not set")
    
    client = OpenAI(api_key=api_key)
    formatted_schema = format_schema(schema)
    
    prompt = f"""Explain this SQL query in 2-3 clear sentences.

Original Question: {natural_language_query}

SQL:
{sql_query}

Schema context:
{formatted_schema}

Return JSON:
{{
    "explanation": "Clear explanation",
    "key_operations": ["Op1", "Op2"],
    "confidence": 0.9
}}
"""
    
    try:
        response = client.chat.completions.create(
            model="gpt-4o-mini",
            messages=[
                {
                    "role": "system",
                    "content": "You are a SQL explainer. Convert SQL to business-friendly explanations."
                },
                {"role": "user", "content": prompt}
            ],
            response_format={"type": "json_object"},
            temperature=0.3
        )
    except Exception as e:
        raise RuntimeError(f"OpenAI API call failed: {str(e)}")
    
    try:
        result = json.loads(response.choices[0].message.content)
    except json.JSONDecodeError as e:
        raise RuntimeError(f"Failed to parse explanation: {str(e)}")
    
    # Extract tables from SQL
    tables = []
    for table in schema.tables:
        if table.name.lower() in sql_query.lower():
            tables.append(table.name)
    
    return ExplanationOutput(
        explanation=result.get("explanation", ""),
        key_operations=result.get("key_operations", []),
        tables_accessed=tables,
        confidence=result.get("confidence", 0.85)
    )
    

# --- NEW FUNCTION FOR SPRINT 2 (User Story A6) ---
from shared.contracts import MultiCandidateOutput

def generate_candidates(intent: QueryIntent, schema: DatabaseSchema, n: int = 3) -> MultiCandidateOutput:
    """
    Generate multiple SQL candidates for the same intent.
    User Story A6: Used when the Router decides the query is complex.
    """
    if not intent or not schema:
        raise ValueError("Intent and Schema are required")

    formatted_schema = format_schema(schema)
    intent_json = json.dumps(intent.model_dump(), indent=2)

    try:
        raws = chat_completion_text(
            model="gpt-5-mini",
            messages=[
                {"role": "system", "content": SYSTEM_PROMPT.format(schema=formatted_schema)},
                {"role": "user", "content": f"Intent:\n{intent_json}\n\nGenerate {n} DISTINCT SQL queries. Each must be exactly ONE SQL query that starts with SELECT (no WITH/CTE) and ends with a single semicolon."}
            ],
            n=n,
        )
    except Exception as e:
        raise RuntimeError(f"OpenAI API call failed: {str(e)}")

    from agents.sql_verifier import verify_sql_against_intent

    candidates = []
    rejected_non_select: list[str] = []
    rejected_safety: list[str] = []
    rejected_a7: list[dict[str, object]] = []
    
    for raw_sql in raws:
        cleaned_sql = clean_sql(raw_sql)

        if not cleaned_sql.upper().startswith("SELECT"):
            rejected_non_select.append(cleaned_sql[:200])
            continue
        
        # Check safety for each specific candidate
        try:
            validate_sql_safety(cleaned_sql)
            is_safe = True
        except ValueError as e:
            is_safe = False
            rejected_safety.append(str(e))

        if is_safe:
            verification = verify_sql_against_intent(cleaned_sql, intent)
            if verification.ok:
                # Add to list
                candidates.append(SQLOutput(
                    sql_query=cleaned_sql,
                    confidence=0.8, # Placeholder confidence
                    tables_used=intent.tables
                ))
            else:
                rejected_a7.append({
                    "sql": cleaned_sql[:400],
                    "issues": [i.model_dump() for i in verification.issues],
                })

    if not candidates:
        raise ValueError(
            "No safe SQL candidates were generated. "
            f"Rejected non-SELECT: {len(rejected_non_select)}, "
            f"rejected by safety: {len(rejected_safety)}, "
            f"rejected by A7: {len(rejected_a7)}. "
            f"Sample non-SELECT: {rejected_non_select[:1]}. "
            f"Sample safety: {rejected_safety[:1]}. "
            f"Sample A7: {rejected_a7[:1]}."
        )

    return MultiCandidateOutput(candidates=candidates)