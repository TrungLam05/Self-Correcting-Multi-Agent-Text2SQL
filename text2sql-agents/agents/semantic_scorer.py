"""Sprint 3 - Role A (A8): Semantic SQL Scorer.

Given a user question, schema, intent, and multiple SQL candidates, score each
candidate for semantic correctness and choose the best.

- Uses an LLM as a judge.
- Returns a numeric score and short rationale for each candidate.

Note: This is intentionally lightweight; A9 will introduce a shared inference layer.
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass
from typing import List, Optional

from agents.inference import chat_completion_text

from shared.contracts import DatabaseSchema, QueryIntent, SQLOutput


@dataclass(frozen=True)
class ScoredCandidate:
    candidate: SQLOutput
    score: float
    rationale: str


_SYSTEM = """You are a strict SQL evaluation judge for PostgreSQL.

You will be given:
- a user question
- a database schema
- a structured QueryIntent
- several SQL candidate queries

Task:
Score each candidate from 0 to 10 for semantic correctness.
A score of 10 means: the SQL answers the question as intended AND matches the intent (metric, aggregation, group_by, filters).
A score of 0 means: completely wrong, unsafe, or unrelated.

Rules:
- Assume SQL will run against the provided schema.
- Prefer queries that use the correct tables/joins and produce the right granularity.
- Penalize missing required grouping, missing filters, wrong aggregation, or wrong metric.
- Do not suggest new SQL. Only score.

Output format:
Return ONLY valid JSON with this exact shape:
{
  "scores": [
    {"index": 0, "score": 7.5, "rationale": "..."},
    {"index": 1, "score": 3.0, "rationale": "..."}
  ]
}
"""


def _format_schema(schema: DatabaseSchema) -> str:
    lines: list[str] = []
    for table in schema.tables:
        lines.append(f"Table: {table.name}")
        for column in table.columns:
            lines.append(f"  - {column.name} ({column.type})")
        lines.append("")
    return "\n".join(lines)


def score_candidates(
    *,
    user_question: str,
    intent: QueryIntent,
    schema: DatabaseSchema,
    candidates: List[SQLOutput],
    model: str = "gpt-5-mini",
) -> List[ScoredCandidate]:
    """Score candidates semantically (A8).

    Raises:
        ValueError if OPENAI_API_KEY is not set or if no candidates are provided.
    """
    if not candidates:
        raise ValueError("No candidates to score")

    schema_text = _format_schema(schema)
    intent_json = json.dumps(intent.model_dump(), indent=2)

    candidate_lines: list[str] = []
    for idx, c in enumerate(candidates):
        candidate_lines.append(f"CANDIDATE {idx}:")
        candidate_lines.append(c.sql_query.strip())
        candidate_lines.append("")

    user = (
        f"USER QUESTION:\n{user_question}\n\n"
        f"SCHEMA:\n{schema_text}\n\n"
        f"INTENT:\n{intent_json}\n\n"
        f"SQL CANDIDATES:\n" + "\n".join(candidate_lines)
    )

    try:
        raw = chat_completion_text(
            model=model,
            messages=[
                {"role": "system", "content": _SYSTEM},
                {"role": "user", "content": user},
            ],
        )[0]
    except Exception as e:
        raise RuntimeError(f"OpenAI API call failed: {str(e)}")

    try:
        data = json.loads(raw)
    except Exception as e:
        raise ValueError(f"Semantic scorer returned non-JSON output: {e}. Raw: {raw[:300]}")

    scores = data.get("scores")
    if not isinstance(scores, list):
        raise ValueError("Semantic scorer JSON missing 'scores' list")

    by_index: dict[int, tuple[float, str]] = {}
    for row in scores:
        if not isinstance(row, dict):
            continue
        try:
            idx = int(row.get("index"))
            score = float(row.get("score"))
            rationale = str(row.get("rationale") or "")
        except Exception:
            continue
        by_index[idx] = (score, rationale)

    out: list[ScoredCandidate] = []
    for i, c in enumerate(candidates):
        score, rationale = by_index.get(i, (0.0, "No score returned for this candidate"))
        # Clamp to 0..10
        score = max(0.0, min(10.0, score))
        out.append(ScoredCandidate(candidate=c, score=score, rationale=rationale))

    return out


def pick_best(scored: List[ScoredCandidate]) -> ScoredCandidate:
    """Pick the best candidate by semantic score (tie-break by generator confidence)."""
    if not scored:
        raise ValueError("No scored candidates")

    return max(scored, key=lambda x: (x.score, x.candidate.confidence))
