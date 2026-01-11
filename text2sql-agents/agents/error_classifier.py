"""
Error Classifier Agent (User Story A4).
Classifies SQL execution errors to guide the Repair Strategy.
"""
from shared.contracts import ErrorType

def classify_error(error_message: str) -> ErrorType:
    """
    Classifies a PostgreSQL error message into a specific category.
    """
    # Convert to lower case so we match "Error" and "error" the same way
    error = error_message.lower()

    # 1. Schema Errors (The most common issue)
    # This happens when the AI hallucinates a column that doesn't exist.
    if any(x in error for x in ["does not exist", "undefined table", "undefined column"]):
        return ErrorType.SCHEMA

    # 2. Syntax Errors (Grammar)
    # This happens if the AI forgets a quote or comma.
    if any(x in error for x in ["syntax error", "unterminated", "unexpected end"]):
        return ErrorType.SYNTAX

    # 3. Logic/Type Errors
    # This happens if we try to divide by zero or compare text to numbers.
    if any(x in error for x in ["division by zero", "operator does not exist", "type mismatch", "invalid input syntax"]):
        return ErrorType.LOGIC

    # Fallback
    return ErrorType.UNKNOWN