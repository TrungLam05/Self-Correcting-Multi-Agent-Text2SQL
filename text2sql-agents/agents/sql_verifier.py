"""Sprint 3 - Role A (A7): AST-based SQL Verifier.

- Parses SQL into an AST (Postgres dialect)
- Extracts "facts" from:
  - outermost SELECT (authoritative)
  - whole query tree (diagnostic)
- Verifies SQL matches QueryIntent requirements:
  aggregation, metric, group_by, filters (+ operator kind)
"""

from __future__ import annotations

from typing import List, Optional, Set, Tuple

import sqlglot
from sqlglot import exp

from shared.contracts import QueryIntent, SqlFacts, SqlVerificationIssue, SqlVerificationResult

_AGG_CLASSES = {
    "SUM": exp.Sum,
    "COUNT": exp.Count,
    "AVG": exp.Avg,
    "MIN": exp.Min,
    "MAX": exp.Max,
}

# sqlglot version differences: NotIn may or may not exist
_NOT_IN_CLASS = getattr(exp, "NotIn", None)

_OP_CLASSES = {
    "=": (exp.EQ,),
    "!=": (exp.NEQ,),
    ">": (exp.GT,),
    ">=": (exp.GTE,),
    "<": (exp.LT,),
    "<=": (exp.LTE,),
    "IN": (exp.In,),
    "LIKE": (exp.Like,),
    "BETWEEN": (exp.Between,),
    "NOT IN": (_NOT_IN_CLASS,) if _NOT_IN_CLASS else tuple(),
}


def _norm_ident(s: str) -> str:
    return (s or "").strip().strip('"').strip("`").lower()


def _norm_intent_ref(s: str) -> str:
    """Normalize an intent-provided column/group key.

    Handles common planner outputs like:
    - "customers.customer_id" -> "customer_id"
    - "c.customer_id" -> "customer_id"

    Leaves function/expression strings as-is (lowercased/trimmed).
    """
    s = _norm_ident(s)
    if not s:
        return s
    if "." in s and "(" not in s and " " not in s:
        return s.split(".")[-1]
    return s


def _parse_select_expression(expr: str, *, dialect: str) -> Optional[exp.Expression]:
    """Best-effort parse of an expression string via SELECT wrapper."""
    try:
        parsed = sqlglot.parse_one(f"SELECT {expr}", read=dialect)
        if isinstance(parsed, exp.Select) and parsed.expressions:
            return parsed.expressions[0]
    except Exception:
        return None
    return None


def _col_name(c: exp.Column) -> str:
    return _norm_ident(c.name)


def _table_name(t: exp.Table) -> str:
    return _norm_ident(t.name)


def _collect_columns(node: Optional[exp.Expression]) -> Set[str]:
    if not node:
        return set()
    return {x for x in (_col_name(c) for c in node.find_all(exp.Column)) if x}


def _collect_tables(node: Optional[exp.Expression]) -> Set[str]:
    if not node:
        return set()
    return {x for x in (_table_name(t) for t in node.find_all(exp.Table)) if x}


def _collect_aggs(node: Optional[exp.Expression]) -> Set[str]:
    if not node:
        return set()
    out: Set[str] = set()
    for name, cls in _AGG_CLASSES.items():
        if any(True for _ in node.find_all(cls)):
            out.add(name)
    return out


def _get_outer_select(root: exp.Expression) -> Optional[exp.Select]:
    # SELECT ...
    if isinstance(root, exp.Select):
        return root

    # WITH ... SELECT ...
    if isinstance(root, exp.With):
        main = root.this
        if isinstance(main, exp.Select):
            return main
        if main is not None:
            return main.find(exp.Select)

    # best-effort fallback
    return root.find(exp.Select)


def _outer_clause(sel: exp.Select, key: str) -> Optional[exp.Expression]:
    return sel.args.get(key) if hasattr(sel, "args") else None


def _collect_group_expr_sql(sel: exp.Select, *, dialect: str) -> Set[str]:
    group_node = _outer_clause(sel, "group")
    if group_node is None:
        return set()
    out: Set[str] = set()
    for e in getattr(group_node, "expressions", []) or []:
        try:
            out.add(_norm_ident(e.sql(dialect=dialect)))
        except Exception:
            continue
    return out


def _collect_predicate_sql(node: Optional[exp.Expression], *, dialect: str) -> Set[str]:
    if node is None:
        return set()
    out: Set[str] = set()
    for e in node.walk():
        try:
            out.add(_norm_ident(e.sql(dialect=dialect)))
        except Exception:
            continue
    return out


def _extract_outer_facts(sel: exp.Select) -> Tuple[Set[str], Set[str], Set[str], Set[str], Set[str], Set[str]]:
    select_exprs = sel.expressions or []
    outer_aggs = _collect_aggs(exp.Select(expressions=select_exprs))

    outer_selected_cols: Set[str] = set()
    for e in select_exprs:
        outer_selected_cols |= _collect_columns(e)

    where_node = _outer_clause(sel, "where")
    group_node = _outer_clause(sel, "group")
    having_node = _outer_clause(sel, "having")

    outer_where_cols = _collect_columns(where_node)
    outer_having_cols = _collect_columns(having_node)
    outer_group_cols = _collect_columns(group_node) if group_node is not None else set()
    outer_tables = _collect_tables(sel)

    return outer_aggs, outer_selected_cols, outer_group_cols, outer_where_cols, outer_having_cols, outer_tables


def _extract_global_facts(root: exp.Expression) -> Tuple[Set[str], Set[str], Set[str]]:
    return _collect_aggs(root), _collect_columns(root), _collect_tables(root)


def _operator_present_for_column(node: Optional[exp.Expression], col: str, operator: str) -> bool:
    """Checks if a predicate of the given operator exists that references the column."""
    if not node or not col or not operator:
        return False

    op = operator.strip().upper()
    col = _norm_ident(col)

    # If NotIn node isn't available, treat NOT IN as NOT(In(...))
    if op == "NOT IN" and not _OP_CLASSES["NOT IN"]:
        for not_node in node.find_all(exp.Not):
            if any(True for _ in not_node.find_all(exp.In)) and col in _collect_columns(not_node):
                return True
        return False

    classes = _OP_CLASSES.get(op, tuple())
    if not classes:
        return False

    for cls in classes:
        if cls is None:
            continue
        for pred in node.find_all(cls):
            if col in _collect_columns(pred):
                return True
    return False


def verify_sql_against_intent(sql: str, intent: QueryIntent, *, dialect: str = "postgres") -> SqlVerificationResult:
    """Main A7 entrypoint."""
    issues: List[SqlVerificationIssue] = []
    facts = SqlFacts(parse_dialect=dialect)

    if not sql or not sql.strip():
        return SqlVerificationResult(
            ok=False,
            issues=[SqlVerificationIssue(kind="empty_sql", message="SQL is empty")],
            facts=facts,
        )

    try:
        root = sqlglot.parse_one(sql, read=dialect)
        facts.statement_type = type(root).__name__
    except Exception as e:
        issues.append(SqlVerificationIssue(kind="parse_error", message=f"Failed to parse SQL: {e}"))
        return SqlVerificationResult(ok=False, issues=issues, facts=facts)

    outer = _get_outer_select(root)
    if outer is None:
        g_aggs, g_cols, g_tabs = _extract_global_facts(root)
        facts.global_aggregations = sorted(g_aggs)
        facts.global_columns = sorted(g_cols)
        facts.global_tables = sorted(g_tabs)
        issues.append(SqlVerificationIssue(kind="no_select", message="No SELECT found to verify"))
        return SqlVerificationResult(ok=False, issues=issues, facts=facts)

    # Extract facts
    o_aggs, o_sel_cols, o_grp_cols, o_where_cols, o_having_cols, o_tabs = _extract_outer_facts(outer)
    g_aggs, g_cols, g_tabs = _extract_global_facts(root)

    facts.outer_aggregations = sorted(o_aggs)
    facts.outer_selected_columns = sorted(o_sel_cols)
    facts.outer_group_by_columns = sorted(o_grp_cols)
    facts.outer_where_columns = sorted(o_where_cols)
    facts.outer_having_columns = sorted(o_having_cols)
    facts.outer_tables = sorted(o_tabs)

    facts.global_aggregations = sorted(g_aggs)
    facts.global_columns = sorted(g_cols)
    facts.global_tables = sorted(g_tabs)

    # ---- Verification (outer is authoritative; global is diagnostic) ----

    # aggregation
    if intent.aggregation:
        needed = intent.aggregation.upper()
        if needed not in o_aggs:
            kind = "missing_aggregation_outer" if needed in g_aggs else "missing_aggregation"
            msg = (
                f"Aggregation '{needed}' appears only in subquery/CTE; not in outer SELECT output."
                if kind.endswith("_outer")
                else f"Missing required aggregation: {needed}"
            )
            issues.append(SqlVerificationIssue(kind=kind, message=msg))

    # metric (must appear in outer SELECT expressions somewhere)
    if intent.metric:
        metric = _norm_ident(intent.metric)
        if metric and metric not in o_sel_cols:
            kind = "missing_metric_outer" if metric in g_cols else "missing_metric"
            msg = (
                f"Metric '{metric}' appears only in subquery/CTE; not referenced in outer SELECT expressions."
                if kind.endswith("_outer")
                else f"Missing required metric column: {metric}"
            )
            issues.append(SqlVerificationIssue(kind=kind, message=msg))

    # group by
    if intent.group_by:
        if not o_grp_cols:
            issues.append(SqlVerificationIssue(kind="missing_group_by_clause", message="Intent requires GROUP BY but SQL has none"))

        outer_group_expr_sql = _collect_group_expr_sql(outer, dialect=dialect)

        for raw in intent.group_by:
            key = _norm_intent_ref(raw)
            if not key:
                continue

            # Simple column key (possibly qualified)
            if "(" not in key and " " not in key:
                if key not in o_grp_cols:
                    issues.append(SqlVerificationIssue(kind="missing_group_by_column", message=f"Missing GROUP BY column: {key}"))
                continue

            # Expression group-by (e.g., EXTRACT(QUARTER FROM order_date))
            expr_ast = _parse_select_expression(key, dialect=dialect)
            expr_cols = _collect_columns(expr_ast)
            expr_norm = _norm_ident(expr_ast.sql(dialect=dialect)) if expr_ast is not None else key

            # Prefer matching expression SQL (robust for canonical EXTRACT)
            if expr_norm in outer_group_expr_sql:
                continue

            # Heuristic fallback: allow common rewrites (EXTRACT vs DATE_PART)
            if "quarter" in key and "order_date" in key:
                if any("order_date" in g and "quarter" in g for g in outer_group_expr_sql):
                    continue

            # Last-resort: require underlying columns present in group-by
            if expr_cols and not expr_cols.issubset(o_grp_cols):
                issues.append(
                    SqlVerificationIssue(
                        kind="missing_group_by_expression",
                        message=f"Missing GROUP BY expression: {key}",
                    )
                )

    # filters (column must appear in outer WHERE or outer HAVING; operator kind should match)
    where_node = _outer_clause(outer, "where")
    having_node = _outer_clause(outer, "having")

    for f in intent.filters:
        raw_col = (f.column or "").strip()
        col_key = _norm_intent_ref(raw_col)
        op = f.operator.upper()

        # Expression-aware column presence check
        expr_ast = None
        expr_cols: Set[str] = set()
        expr_norm: Optional[str] = None
        is_expr = ("(" in col_key) or (" " in col_key)
        if is_expr:
            expr_ast = _parse_select_expression(col_key, dialect=dialect)
            expr_cols = _collect_columns(expr_ast)
            if expr_ast is not None:
                try:
                    expr_norm = _norm_ident(expr_ast.sql(dialect=dialect))
                except Exception:
                    expr_norm = None

        # Column presence: for simple columns we check direct membership; for expressions we check underlying columns
        if (not is_expr and col_key not in o_where_cols and col_key not in o_having_cols) or (
            is_expr and expr_cols and not (expr_cols & (o_where_cols | o_having_cols))
        ):
            diag_key = col_key if not is_expr else (expr_cols.pop() if expr_cols else col_key)
            kind = "missing_filter_outer" if diag_key in g_cols else "missing_filter"
            msg = (
                f"Filter column '{col_key}' appears only in subquery/CTE; not in outer WHERE/HAVING."
                if kind.endswith("_outer")
                else f"Missing required filter on column: {col_key}"
            )
            issues.append(SqlVerificationIssue(kind=kind, message=msg))
            continue

        # strict operator check (recommended for real-world usefulness)
        if not is_expr:
            ok_op = _operator_present_for_column(where_node, col_key, op) or _operator_present_for_column(having_node, col_key, op)
        else:
            # For expression filters, first check operator exists with the underlying columns.
            # This is conservative (may allow rewrites) but blocks missing-operator errors.
            ok_op = False
            for c in (expr_cols or set()):
                if _operator_present_for_column(where_node, c, op) or _operator_present_for_column(having_node, c, op):
                    ok_op = True
                    break

            # If we can canonicalize the expression, require it to appear somewhere in the predicate SQL strings.
            if ok_op and expr_norm:
                where_sql = _collect_predicate_sql(where_node, dialect=dialect)
                having_sql = _collect_predicate_sql(having_node, dialect=dialect)
                if expr_norm not in where_sql and expr_norm not in having_sql:
                    # Heuristic fallback for common EXTRACT/DATE_PART quarter rewrites
                    if "quarter" in col_key and any("quarter" in s and "order_date" in s for s in (where_sql | having_sql)):
                        pass
                    else:
                        ok_op = False
        if not ok_op:
            issues.append(
                SqlVerificationIssue(
                    kind="missing_filter_operator",
                    message=f"Filter on '{col_key}' does not use required operator '{op}' (or operator not recognized).",
                )
            )

    return SqlVerificationResult(ok=(len(issues) == 0), issues=issues, facts=facts)