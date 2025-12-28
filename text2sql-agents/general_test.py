"""
Demo script to test the complete Text2SQL pipeline.
Shows Router -> Intent Planner -> SQL Generator flow with LLM responses.
"""

import json
from dotenv import load_dotenv
from agents.router import classify_complexity
from agents.intent_planner import extract_intent
from agents.sql_generator import generate_sql
from shared.contracts import DatabaseSchema, SchemaColumn, SchemaTable

# Load environment variables
load_dotenv()

# Define sample schema
SAMPLE_SCHEMA = DatabaseSchema(
    tables=[
        SchemaTable(
            name="orders",
            columns=[
                SchemaColumn(name="order_id", type="INTEGER", primary_key=True),
                SchemaColumn(name="customer_id", type="INTEGER"),
                SchemaColumn(name="total_amount", type="DECIMAL"),
                SchemaColumn(name="order_date", type="DATE"),
                SchemaColumn(name="status", type="VARCHAR"),
            ]
        ),
        SchemaTable(
            name="customers",
            columns=[
                SchemaColumn(name="customer_id", type="INTEGER", primary_key=True),
                SchemaColumn(name="name", type="VARCHAR"),
                SchemaColumn(name="email", type="VARCHAR"),
                SchemaColumn(name="industry", type="VARCHAR"),
            ]
        ),
        SchemaTable(
            name="products",
            columns=[
                SchemaColumn(name="product_id", type="INTEGER", primary_key=True),
                SchemaColumn(name="product_name", type="VARCHAR"),
                SchemaColumn(name="category", type="VARCHAR"),
                SchemaColumn(name="price", type="DECIMAL"),
            ]
        )
    ]
)

# Test questions with varying complexity
TEST_QUESTIONS = [
    "What is our total revenue?",
    "How many orders do we have?",
    "Show me total sales by customer industry",
    "What are the top 5 customers by revenue in 2024?",
    "Compare sales trends between Q1 and Q2",
]


def print_separator(title: str = ""):
    """Print a formatted separator."""
    print("\n" + "=" * 80)
    if title:
        print(f"  {title}")
        print("=" * 80)


def run_pipeline(question: str, schema: DatabaseSchema):
    """Run the complete Text2SQL pipeline for a question."""
    
    print_separator(f"QUESTION: {question}")
    
    # Step 1: Router
    print("\n📍 STEP 1: ROUTER (Complexity Classification)")
    print("-" * 80)
    router_output = classify_complexity(question)
    print(f"Complexity: {router_output.complexity}")
    print(f"Strategy: {router_output.strategy}")
    print(f"Reasoning: {router_output.reasoning}")
    
    # Step 2: Intent Planner
    print("\n📍 STEP 2: INTENT PLANNER (LLM Extraction)")
    print("-" * 80)
    try:
        intent = extract_intent(question, schema)
        print("✅ Intent extracted successfully!")
        print(f"\nStructured Intent:")
        intent_dict = intent.model_dump()
        print(json.dumps(intent_dict, indent=2))
    except Exception as e:
        print(f"❌ Intent extraction failed: {e}")
        return
    
    # Step 3: SQL Generator
    print("\n📍 STEP 3: SQL GENERATOR (LLM SQL Generation)")
    print("-" * 80)
    try:
        sql_output = generate_sql(intent, schema)
        print("✅ SQL generated successfully!")
        print(f"\nGenerated SQL:")
        print("-" * 40)
        print(sql_output.sql_query)
        print("-" * 40)
        print(f"\nMetadata:")
        print(f"  Confidence: {sql_output.confidence:.2%}")
        print(f"  Tables Used: {', '.join(sql_output.tables_used)}")
    except Exception as e:
        print(f"❌ SQL generation failed: {e}")
        return
    
    # Summary
    print("\n📊 PIPELINE SUMMARY")
    print("-" * 80)
    print(f"Question: {question}")
    print(f"Complexity: {router_output.complexity} → {router_output.strategy}")
    print(f"Tables: {', '.join(intent.tables)}")
    print(f"Aggregation: {intent.aggregation or 'None'}")
    print(f"Filters: {len(intent.filters)} filter(s)")
    print(f"SQL Length: {len(sql_output.sql_query)} characters")
    print(f"Confidence: {sql_output.confidence:.2%}")


def main():
    """Run demo for all test questions."""
    
    print_separator("TEXT2SQL PIPELINE DEMO")
    print("\nTesting complete pipeline: Router → Intent Planner → SQL Generator")
    print(f"Database Schema: {len(SAMPLE_SCHEMA.tables)} tables")
    print(f"Test Questions: {len(TEST_QUESTIONS)}")
    
    for i, question in enumerate(TEST_QUESTIONS, 1):
        print(f"\n\n{'#' * 80}")
        print(f"# TEST {i}/{len(TEST_QUESTIONS)}")
        print(f"{'#' * 80}")
        
        try:
            run_pipeline(question, SAMPLE_SCHEMA)
        except Exception as e:
            print(f"\n❌ Pipeline failed: {e}")
    
    print_separator("DEMO COMPLETE")


if __name__ == "__main__":
    main()