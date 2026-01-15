#!/usr/bin/env python3
"""
Prompt Optimization Script (User Story A10).

This script:
1. Loads failure logs from a JSON file
2. Analyzes patterns using the Prompt Optimizer
3. Generates improvement suggestions
4. Optionally applies improvements to agent files
5. Generates a report

Usage:
    python scripts/optimize_prompts.py --logs logs/failures.json --analyze
    python scripts/optimize_prompts.py --logs logs/failures.json --apply agents/sql_generator.py
    python scripts/optimize_prompts.py --logs logs/failures.json --report report.txt
"""

import argparse
import sys
import os
from pathlib import Path

# Add parent directory to path so we can import agents
sys.path.insert(0, str(Path(__file__).parent.parent))

from agents.prompt_optimizer import (
    load_failure_logs,
    analyze_failure_patterns,
    apply_prompt_improvements,
    generate_report
)


def main():
    parser = argparse.ArgumentParser(
        description="Analyze failure logs and optimize agent prompts"
    )
    
    parser.add_argument(
        "--logs",
        required=True,
        help="Path to failure logs JSON file"
    )
    
    parser.add_argument(
        "--analyze",
        action="store_true",
        help="Analyze patterns and show suggestions"
    )
    
    parser.add_argument(
        "--apply",
        metavar="AGENT_FILE",
        help="Apply improvements to specified agent file (e.g., agents/sql_generator.py)"
    )
    
    parser.add_argument(
        "--report",
        metavar="OUTPUT_FILE",
        help="Generate report and save to file"
    )
    
    parser.add_argument(
        "--no-backup",
        action="store_true",
        help="Don't create backup when applying improvements"
    )
    
    parser.add_argument(
        "--min-pattern-count",
        type=int,
        default=2,
        help="Minimum occurrences to consider a pattern (default: 2)"
    )
    
    args = parser.parse_args()
    
    # Load failure logs
    print(f"Loading failure logs from: {args.logs}")
    logs = load_failure_logs(args.logs)
    
    if not logs:
        print("❌ No failure logs found or file is empty.")
        return 1
    
    print(f"✅ Loaded {len(logs)} failure logs")
    
    # Analyze patterns
    print("\n" + "=" * 60)
    print("ANALYZING FAILURE PATTERNS...")
    print("=" * 60)
    
    analysis = analyze_failure_patterns(logs)
    
    print(f"\nTotal Failures: {analysis['total_failures']}")
    print(f"Patterns Detected: {len(analysis['patterns'])}")
    print(f"Suggestions Generated: {len(analysis['suggestions'])}")
    
    # Display analysis if requested
    if args.analyze:
        print("\n" + "=" * 60)
        print("DETAILED ANALYSIS")
        print("=" * 60)
        
        print("\nError Distribution:")
        for error_type, count in analysis['error_distribution'].items():
            print(f"  {error_type}: {count}")
        
        print("\nPatterns:")
        for pattern in analysis['patterns']:
            print(f"\n  [{pattern['severity']}] {pattern['type']}")
            print(f"    Count: {pattern['count']}")
            if 'example' in pattern:
                print(f"    Example: {pattern['example'][:80]}...")
        
        print("\nSuggestions:")
        for i, suggestion in enumerate(analysis['suggestions'], 1):
            print(f"\n  {i}. Target: {suggestion['target_agent']}")
            print(f"     Reason: {suggestion['reason']}")
            print(f"     Addition: {suggestion['prompt_addition']}")
    
    # Apply improvements if requested
    if args.apply:
        if not analysis['suggestions']:
            print("\n⚠️  No suggestions to apply.")
        else:
            print(f"\n{'=' * 60}")
            print(f"APPLYING IMPROVEMENTS TO: {args.apply}")
            print("=" * 60)
            
            result = apply_prompt_improvements(
                args.apply,
                analysis['suggestions'],
                backup=not args.no_backup
            )
            
            if result['success']:
                print(f"✅ Successfully applied {result['suggestions_applied']} suggestions")
                if result.get('backup_path'):
                    print(f"📁 Backup created: {result['backup_path']}")
                print(f"📝 Modified file: {result['agent_filepath']}")
            else:
                print(f"❌ Failed to apply improvements: {result['error']}")
                return 1
    
    # Generate report if requested
    if args.report:
        print(f"\n{'=' * 60}")
        print(f"GENERATING REPORT: {args.report}")
        print("=" * 60)
        
        report = generate_report(analysis)
        
        # Ensure directory exists
        os.makedirs(os.path.dirname(args.report) or '.', exist_ok=True)
        
        with open(args.report, 'w') as f:
            f.write(report)
        
        print(f"✅ Report saved to: {args.report}")
        print("\nReport preview:")
        print(report[:500] + "..." if len(report) > 500 else report)
    
    print("\n" + "=" * 60)
    print("OPTIMIZATION COMPLETE")
    print("=" * 60)
    
    return 0


if __name__ == "__main__":
    sys.exit(main())