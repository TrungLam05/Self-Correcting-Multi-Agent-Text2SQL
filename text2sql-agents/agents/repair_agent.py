"""
Repair Agent (User Story A5).
Fixes failing SQL queries using error context and schema.
"""
import os
from shared.contracts import DatabaseSchema, RepairInput, RepairOutput, ErrorType
from agents.inference import chat_completion_text
from agents.sql_generator import format_schema, clean_sql, validate_sql_safety

# This prompt teaches the AI how to fix specific mistakes
SYSTEM_PROMPT = """You are a SQL Repair Expert for PostgreSQL.

Context:
1. Schema: {schema}
2. Error Type: {error_type}

Rules:
- If Error is SCHEMA: Check table/column names closely against the provided schema.
- If Error is SYNTAX: Fix missing commas, quotes, or keywords.
- If Error is LOGIC: Fix data type mismatches (e.g., casting strings to ints).
- Maintain the original intent of the query.
- Return ONLY the fixed SQL.
"""

def repair_sql(request: RepairInput, schema: DatabaseSchema) -> RepairOutput:
    """
    Attempts to fix a broken SQL query.
    """
    # 2. Prepare the data
    formatted_schema = format_schema(schema)

    # 3. Create the prompt (The "Patient Chart")
    prompt = f"""
    Bad SQL:
    {request.bad_sql}

    Error Message:
    {request.error_message}

    Please fix this query.
    """

    try:
        fixed_raw = chat_completion_text(
            model="gpt-5-mini",
            messages=[
                {
                    "role": "system",
                    "content": SYSTEM_PROMPT.format(
                        schema=formatted_schema,
                        error_type=request.error_type.value,
                    ),
                },
                {"role": "user", "content": prompt},
            ],
        )[0]
    except Exception as e:
        raise RuntimeError(f"OpenAI Repair call failed: {str(e)}")

    # 5. Clean and Validate
    fixed_sql = clean_sql(fixed_raw)
    validate_sql_safety(fixed_sql)

    return RepairOutput(
        fixed_sql=fixed_sql,
        reasoning=f"Fixed {request.error_type.value} issue.",
        confidence=0.85
    )