"""
Prompt Optimizer Agent (User Story A10).
Analyzes failure logs and suggests prompt improvements.
"""
import json
import os
from typing import List, Dict, Any
from collections import Counter
from datetime import datetime
from shared.contracts import ErrorType, FailureLog


def load_failure_logs(filepath: str) -> List[Dict[str, Any]]:
    """
    Load failure logs from a JSON file.
    
    Args:
        filepath: Path to the failure logs JSON file
    
    Returns:
        List of failure log dictionaries
    """
    if not os.path.exists(filepath):
        return []
    
    try:
        with open(filepath, 'r') as f:
            logs = json.load(f)
            return logs if isinstance(logs, list) else []
    except (json.JSONDecodeError, IOError) as e:
        print(f"Warning: Failed to load logs from {filepath}: {str(e)}")
        return []


def save_failure_log(log: FailureLog, filepath: str) -> None:
    """
    Append a failure log to the JSON file.
    
    Args:
        log: FailureLog instance to save
        filepath: Path to the failure logs JSON file
    """
    # Load existing logs
    existing_logs = load_failure_logs(filepath)
    
    # Add new log
    existing_logs.append(log.model_dump())
    
    # Save back
    os.makedirs(os.path.dirname(filepath) or '.', exist_ok=True)
    with open(filepath, 'w') as f:
        json.dump(existing_logs, f, indent=2)


def analyze_failure_patterns(failure_logs: List[Dict[str, Any]]) -> Dict[str, Any]:
    """
    Analyze failure logs and return actionable insights.
    
    Args:
        failure_logs: List of dicts with keys:
            - natural_language_query: str
            - original_sql: str  
            - error_type: ErrorType
            - error_message: str
    
    Returns:
        Dict with patterns and suggested prompt fixes
    """
    if not failure_logs:
        return {
            "total_failures": 0,
            "error_distribution": {},
            "patterns": [],
            "suggestions": []
        }
    
    # Count error types
    error_counts = Counter(log.get("error_type") for log in failure_logs)
    
    patterns = []
    suggestions = []
    
    # Pattern 1: Schema errors (hallucinated columns)
    schema_errors = [log for log in failure_logs if log.get("error_type") == ErrorType.SCHEMA]
    if len(schema_errors) >= 3:
        patterns.append({
            "type": "HALLUCINATED_COLUMNS",
            "count": len(schema_errors),
            "severity": "HIGH" if len(schema_errors) >= 10 else "MEDIUM",
            "example": schema_errors[0]["error_message"][:100]
        })
        suggestions.append({
            "prompt_addition": "- CRITICAL: Verify ALL column names exist in schema before generating SQL. Double-check spelling.",
            "target_agent": "sql_generator",
            "reason": f"Found {len(schema_errors)} hallucinated column errors"
        })
    
    # Pattern 2: Missing JOIN qualifiers
    join_errors = [log for log in schema_errors 
                   if "JOIN" in log.get("original_sql", "").upper() 
                   and "ambiguous" in log.get("error_message", "").lower()]
    if len(join_errors) >= 2:
        patterns.append({
            "type": "MISSING_JOIN_QUALIFIERS", 
            "count": len(join_errors),
            "severity": "MEDIUM"
        })
        suggestions.append({
            "prompt_addition": "- Always use table aliases and fully qualify columns in JOINs (e.g., o.order_id, not order_id).",
            "target_agent": "sql_generator",
            "reason": f"Found {len(join_errors)} ambiguous JOIN errors"
        })
    
    # Pattern 3: Syntax errors (unquoted strings)
    syntax_errors = [log for log in failure_logs if log.get("error_type") == ErrorType.SYNTAX]
    if len(syntax_errors) >= 2:
        patterns.append({
            "type": "UNQUOTED_STRINGS",
            "count": len(syntax_errors),
            "severity": "HIGH" if len(syntax_errors) >= 5 else "MEDIUM"
        })
        suggestions.append({
            "prompt_addition": "- String literals MUST use single quotes (e.g., 'active', not active).",
            "target_agent": "sql_generator",
            "reason": f"Found {len(syntax_errors)} syntax errors"
        })
    
    # Pattern 4: Type mismatches
    logic_errors = [log for log in failure_logs if log.get("error_type") == ErrorType.LOGIC]
    if len(logic_errors) >= 2:
        patterns.append({
            "type": "TYPE_MISMATCH",
            "count": len(logic_errors),
            "severity": "MEDIUM"
        })
        suggestions.append({
            "prompt_addition": "- Cast values to match column types (e.g., '123'::INTEGER, value::TEXT).",
            "target_agent": "sql_generator",
            "reason": f"Found {len(logic_errors)} type mismatch errors"
        })
    
    return {
        "total_failures": len(failure_logs),
        "error_distribution": dict(error_counts),
        "patterns": patterns,
        "suggestions": suggestions
    }


def generate_improved_prompt(current_prompt: str, suggestions: List[Dict]) -> str:
    """
    Apply suggestions to current prompt.
    
    Args:
        current_prompt: Current SYSTEM_PROMPT string
        suggestions: List of suggestion dicts from analyze_failure_patterns
    
    Returns:
        Updated prompt with suggestions added
    """
    if not suggestions:
        return current_prompt
    
    improved = current_prompt
    
    # Find the Rules section
    if "Rules:" in improved:
        # Add new rules after the "Rules:" header
        new_rules = "\n".join(s["prompt_addition"] for s in suggestions if s.get("prompt_addition"))
        improved = improved.replace("Rules:\n", f"Rules:\n{new_rules}\n")
    else:
        # No Rules section, add at end
        new_rules = "\n".join(s["prompt_addition"] for s in suggestions if s.get("prompt_addition"))
        improved += f"\n\nAdditional Rules (from failure analysis):\n{new_rules}"
    
    return improved


def apply_prompt_improvements(
    agent_filepath: str,
    suggestions: List[Dict],
    backup: bool = True
) -> Dict[str, Any]:
    """
    Apply prompt improvements directly to an agent file.
    
    Args:
        agent_filepath: Path to the agent Python file (e.g., agents/sql_generator.py)
        suggestions: List of suggestion dicts
        backup: Whether to create a backup of the original file
    
    Returns:
        Dict with status and details
    """
    if not os.path.exists(agent_filepath):
        return {
            "success": False,
            "error": f"Agent file not found: {agent_filepath}"
        }
    
    # Read current file
    with open(agent_filepath, 'r') as f:
        content = f.read()
    
    # Create backup if requested
    if backup:
        backup_path = f"{agent_filepath}.backup.{datetime.now().strftime('%Y%m%d_%H%M%S')}"
        with open(backup_path, 'w') as f:
            f.write(content)
    
    # Find SYSTEM_PROMPT
    if 'SYSTEM_PROMPT = """' not in content:
        return {
            "success": False,
            "error": "Could not find SYSTEM_PROMPT in agent file"
        }
    
    # Extract current prompt
    start_idx = content.find('SYSTEM_PROMPT = """') + len('SYSTEM_PROMPT = """')
    end_idx = content.find('"""', start_idx)
    current_prompt = content[start_idx:end_idx]
    
    # Generate improved prompt
    improved_prompt = generate_improved_prompt(current_prompt, suggestions)
    
    # Replace in content
    new_content = content[:start_idx] + improved_prompt + content[end_idx:]
    
    # Write back
    with open(agent_filepath, 'w') as f:
        f.write(new_content)
    
    return {
        "success": True,
        "backup_path": backup_path if backup else None,
        "suggestions_applied": len(suggestions),
        "agent_filepath": agent_filepath
    }


def generate_report(analysis: Dict[str, Any]) -> str:
    """
    Generate a human-readable report from failure analysis.
    
    Args:
        analysis: Output from analyze_failure_patterns()
    
    Returns:
        Formatted report string
    """
    lines = []
    lines.append("=" * 60)
    lines.append("PROMPT OPTIMIZATION REPORT")
    lines.append("=" * 60)
    lines.append(f"\nTotal Failures Analyzed: {analysis['total_failures']}")
    
    lines.append("\nError Distribution:")
    for error_type, count in analysis['error_distribution'].items():
        lines.append(f"  - {error_type}: {count}")
    
    lines.append(f"\nPatterns Detected: {len(analysis['patterns'])}")
    for pattern in analysis['patterns']:
        lines.append(f"\n  [{pattern['severity']}] {pattern['type']}")
        lines.append(f"    Occurrences: {pattern['count']}")
        if 'example' in pattern:
            lines.append(f"    Example: {pattern['example']}")
    
    lines.append(f"\n\nSuggested Improvements: {len(analysis['suggestions'])}")
    for i, suggestion in enumerate(analysis['suggestions'], 1):
        lines.append(f"\n  {i}. {suggestion['target_agent']}")
        lines.append(f"     Reason: {suggestion['reason']}")
        lines.append(f"     Add to prompt: {suggestion['prompt_addition']}")
    
    lines.append("\n" + "=" * 60)
    
    return "\n".join(lines)