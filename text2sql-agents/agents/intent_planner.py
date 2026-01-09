""" Extract structured intent using LLM """
import json
import os
from openai import OpenAI
from shared.contracts import DatabaseSchema, QueryIntent
from dotenv import load_dotenv
load_dotenv()

SYSTEM_PROMPT = """ You are an intent extraction agent for Text2SQL
Your job will be to convert natural language questions into structured JSON.
Output format:
{{
    "metric": "column_name",
    "aggregation": "SUM|COUNT|AVG|MAX|MIN|null",
    "filters": [
        {{
            "column": "column_name",
            "operator": ">=|<=|=|!=|>|<|BETWEEN|IN|NOT IN",
            "value": "value"
        }}
    ],
    "group_by": ["column_name1", "column_name2"],
    "order_by": {{
        "column": "column_name",
        "direction": "ASC|DESC"
    }} or null,
    "limit": null|number,
    "tables": ["table_name1", "table_name2"]
}}

Schema:
{schema}
Return ONLY valid JSON matching the format above
"""

def format_schema(schema: DatabaseSchema) -> str:
    """Format the database schema into a string for the LLM"""
    lines = []
    for table in schema.tables:
        lines.append(f"Table: {table.name}")
        for column in table.columns:
            lines.append(f"  - {column.name} ({column.type})")
    return "\n".join(lines)

def extract_intent(question: str, schema: DatabaseSchema) -> QueryIntent:
    """Extract structured intent using LLM"""
    if not question or not question.strip():
        raise ValueError("Question cannot be empty or whitespace")
    if not schema or not schema.tables:
        raise ValueError("Schema cannot be empty or invalid")
    client = OpenAI(api_key=os.getenv("OPENAI_API_KEY"))

    if not os.getenv("OPENAI_API_KEY"):
        raise ValueError("OPENAI_API_KEY environment variable is not set")
    formatted_schema = format_schema(schema)

    try:
        response = client.chat.completions.create(
                model="gpt-5-mini",
                messages=[
                    {
                        "role": "system",
                        "content": SYSTEM_PROMPT.format(schema=formatted_schema),
                    },
                    {"role": "user", "content": f"Question: {question}\n\nExtract intent as JSON."},
                ],
                response_format={"type": "json_object"}
            )
    except Exception as e:
        raise ValueError(f"Error extracting intent: {str(e)}")    
    try:
        intent_dict = json.loads(response.choices[0].message.content)
    except json.JSONDecodeError as e:
        raise ValueError(f"LLM returned invalid JSON: {str(e)}")
    try:
        return QueryIntent(**intent_dict)
    except Exception as e:
        raise ValueError(f"LLM output doesn't match the expected schema: {str(e)}")