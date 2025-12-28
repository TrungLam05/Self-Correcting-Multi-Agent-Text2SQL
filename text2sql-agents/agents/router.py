"""Router Agent: Classify query complexity using heuristics."""

import re
from typing import Literal
from shared.contracts import RouterOutput


class ComplexityFeatures:
    """Extract features from question for classification."""
    
    def __init__(self, question: str):
        self.question = question.lower()
        self.tokens = re.findall(r'\b\w+\b', self.question)
    
    def has_comparison(self) -> bool:
        patterns = [
            r'\b(compare|versus|vs\.?|compared to|difference)\b',
            r'\b(higher|lower|more|less)\s+than\b'
        ]
        return any(re.search(p, self.question) for p in patterns)
    
    def has_time_trend(self) -> bool:
        patterns = [
            r'\b(trend|over time|growth|decline)\b',
            r'\b(month over month|yoy|year over year)\b',
            r'\b(by month|by quarter|by year)\b'
        ]
        return any(re.search(p, self.question) for p in patterns)
    
    def has_grouping(self) -> bool:
        pattern = r'\bby\s+\w+\s+(and|,)\s+\w+'
        return bool(re.search(pattern, self.question))
    
    def has_ranking(self) -> bool:
        patterns = [
            r'\b(top|bottom|best|worst|highest|lowest)\s+(\d+|ten|five|twenty)\b',
            r'\b(rank|ranking)\b'
        ]
        return any(re.search(p, self.question) for p in patterns)
    
    def has_calculation(self) -> bool:
        patterns = [
            r'\b(percent|percentage|ratio|rate)\b',
            r'\b(conversion|retention|churn)\b'
        ]
        return any(re.search(p, self.question) for p in patterns)
    
    def word_count(self) -> int:
        return len(self.tokens)


def classify_complexity(question: str) -> RouterOutput:
    """
    Classify query complexity using rule-based heuristics.
    
    Args:
        question: Natural language analytics question
    
    Returns:
        RouterOutput with complexity classification
    """
    if not question or not question.strip():
        return RouterOutput(
            complexity="simple",
            strategy="single_candidate",
            reasoning="Empty query defaults to simple"
        )
    
    features = ComplexityFeatures(question)
    
    # Score complexity
    score = 0
    signals = []
    
    if features.has_comparison():
        score += 2
        signals.append("comparison")
    
    if features.has_time_trend():
        score += 2
        signals.append("time-series")
    
    if features.has_grouping():
        score += 1.5
        signals.append("multiple groupings")
    
    if features.has_ranking():
        score += 1.5
        signals.append("ranking")
    
    if features.has_calculation():
        score += 1
        signals.append("calculation")
    
    if features.word_count() > 20:
        score += 1
        signals.append("long query")
    
    # Classify
    complexity = "moderate" if score >= 2.0 else "simple"
    strategy = "multi_candidate" if complexity == "moderate" else "single_candidate"
    
    reasoning = f"Score: {score:.1f}. "
    if signals:
        reasoning += f"Detected: {', '.join(signals)}"
    else:
        reasoning += "Simple aggregation pattern"
    
    return RouterOutput(
        complexity=complexity,
        strategy=strategy,
        reasoning=reasoning
    )