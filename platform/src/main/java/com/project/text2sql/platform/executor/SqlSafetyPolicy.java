package com.project.text2sql.platform.executor;

import com.project.text2sql.platform.config.Text2SqlExecutorProperties;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.truncate.Truncate;
import net.sf.jsqlparser.statement.update.Update;
import org.springframework.stereotype.Component;

@Component
public class SqlSafetyPolicy {
    private final Text2SqlExecutorProperties props;

    public SqlSafetyPolicy(Text2SqlExecutorProperties props) {
        this.props = props;
    }

    public String sanitizeAndEnforceLimit(String rawSql) {
        // Validate input
        if (rawSql == null || rawSql.isBlank()) {
            throw new SqlSanitizationException(
                "SQL cannot be empty",
                SqlSanitizationException.ViolationType.INVALID_SQL
            );
        }
        
        if (rawSql.length() > props.getMaxSqlLength()) {
            throw new SqlSanitizationException(
                String.format("SQL exceeds maximum length of %d characters", props.getMaxSqlLength()),
                SqlSanitizationException.ViolationType.EXCEEDS_LENGTH_LIMIT
            );
        }

        // Parse SQL
        final Statements stmts;
        try {
            stmts = CCJSqlParserUtil.parseStatements(rawSql);
        } catch (Exception e) {
            throw new SqlSanitizationException(
                "Invalid SQL syntax: " + e.getMessage(),
                SqlSanitizationException.ViolationType.PARSE_ERROR,
                e
            );
        }

        // Validate single statement
        if (stmts.getStatements().size() != 1) {
            throw new SqlSanitizationException(
                String.format("Multiple statements detected (%d). Only single SELECT queries allowed.", 
                    stmts.getStatements().size()),
                SqlSanitizationException.ViolationType.MULTIPLE_STATEMENTS
            );
        }

        // Validate it's a SELECT
        Statement s = stmts.getStatements().get(0);
        if (!(s instanceof Select select)) {
            String statementType = getStatementTypeName(s);
            throw new SqlSanitizationException(
                String.format("Statement type '%s' is not allowed. Only SELECT queries are permitted.", statementType),
                SqlSanitizationException.ViolationType.FORBIDDEN_STATEMENT_TYPE
            );
        }

        // Reject locking clauses
        rejectLocking(select);
        
        // Enforce LIMIT
        enforceLimit(select);
        
        return select.toString();
    }

    private String getStatementTypeName(Statement statement) {
        if (statement instanceof Insert) return "INSERT";
        if (statement instanceof Update) return "UPDATE";
        if (statement instanceof Delete) return "DELETE";
        if (statement instanceof Truncate) return "TRUNCATE";
        return statement.getClass().getSimpleName();
    }

    private void rejectLocking(Select select) {
    // Check FOR clause (newer JSQLParser versions)
        if (select.getForClause() != null) {
            throw new SqlSanitizationException(
                "Locking clauses (FOR UPDATE, FOR SHARE, etc.) are not allowed",
                SqlSanitizationException.ViolationType.LOCKING_CLAUSE_DETECTED
            );
        }
    
        // Check PlainSelect's FOR UPDATE (older approach)
        if (select.getPlainSelect() != null) {
            PlainSelect ps = select.getPlainSelect();
            
            // Check forUpdateTable
            if (ps.getForUpdateTable() != null) {
                throw new SqlSanitizationException(
                    "FOR UPDATE clause is not allowed",
                    SqlSanitizationException.ViolationType.LOCKING_CLAUSE_DETECTED
                );
            }
            
            // Check wait (some versions use this)
            if (ps.getWait() != null) {
                throw new SqlSanitizationException(
                    "FOR UPDATE clause is not allowed",
                    SqlSanitizationException.ViolationType.LOCKING_CLAUSE_DETECTED
                );
            }
            
            // Alternative: check SQL string contains FOR UPDATE
            String sqlStr = select.toString().toUpperCase();
            if (sqlStr.contains("FOR UPDATE") || sqlStr.contains("FOR SHARE")) {
                throw new SqlSanitizationException(
                    "Locking clauses (FOR UPDATE, FOR SHARE, etc.) are not allowed",
                    SqlSanitizationException.ViolationType.LOCKING_CLAUSE_DETECTED
                );
            }
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