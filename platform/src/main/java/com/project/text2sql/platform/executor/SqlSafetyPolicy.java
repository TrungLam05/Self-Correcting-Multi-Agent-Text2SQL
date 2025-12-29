package com.project.text2sql.platform.executor;

import com.project.text2sql.platform.config.Text2SqlExecutorProperties;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import org.springframework.stereotype.Component;

@Component
public class SqlSafetyPolicy {
    private final Text2SqlExecutorProperties props;

    public SqlSafetyPolicy(Text2SqlExecutorProperties props) {
        this.props = props;
    }

    public String sanitizeAndEnforceLimit(String rawSql) {
        if (rawSql == null || rawSql.isBlank()) {
            throw new IllegalArgumentException("sql is required");
        }
        if (rawSql.length() > props.getMaxSqlLength()) {
            throw new IllegalArgumentException("sql exceeds maximum length");
        }

        final Statements stmts;
        try {
            stmts = CCJSqlParserUtil.parseStatements(rawSql);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid sql");
        }

        if (stmts.getStatements().size() != 1) {
            throw new IllegalArgumentException("only single-statement sql is allowed");
        }

        Statement s = stmts.getStatements().get(0);
        if (!(s instanceof Select select)) {
            throw new IllegalArgumentException("only SELECT statements are allowed");
        }

        rejectLocking(select);
        enforceLimit(select);
        return select.toString();
    }

    private static void rejectLocking(Select select) {
        if (select.getForClause() != null) {
            throw new IllegalArgumentException("locking clauses are not allowed");
        }

        PlainSelect ps = select.getPlainSelect();
        if (ps != null && ps.getForUpdateTable() != null) {
            throw new IllegalArgumentException("locking clauses are not allowed");
        }
    }

    private void enforceLimit(Select select) {
        int defaultLimit = Math.max(1, props.getDefaultLimit());
        int maxLimit = Math.max(defaultLimit, props.getMaxLimit());

        Limit limit = select.getLimit();
        if (limit == null || limit.getRowCount() == null) {
            select.setLimit(buildLimit(defaultLimit));
            return;
        }

        long requested = parseLongLimit(limit);
        if (requested <= 0) {
            select.setLimit(buildLimit(defaultLimit));
            return;
        }

        long clamped = Math.min(requested, maxLimit);
        select.setLimit(buildLimit(clamped));
    }

    private static long parseLongLimit(Limit limit) {
        if (limit.getRowCount() instanceof LongValue lv) {
            return lv.getValue();
        }
        return -1;
    }

    private static Limit buildLimit(long value) {
        Limit limit = new Limit();
        limit.setRowCount(new LongValue(value));
        return limit;
    }
}