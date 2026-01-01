package com.project.text2sql.platform.executor;

import com.project.text2sql.platform.config.Text2SqlExecutorProperties;
import com.project.text2sql.platform.executor.dto.ExecuteSqlResponse;
import com.project.text2sql.platform.executor.dto.SqlExecutionError;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Service
public class SqlExecutorService {
    private final JdbcTemplate jdbcTemplate;
    private final SqlSafetyPolicy safetyPolicy;
    private final Text2SqlExecutorProperties props;

    public SqlExecutorService(JdbcTemplate jdbcTemplate, SqlSafetyPolicy safetyPolicy, Text2SqlExecutorProperties props) {
        this.jdbcTemplate = jdbcTemplate;
        this.safetyPolicy = safetyPolicy;
        this.props = props;
    }

    public ExecuteSqlResponse execute(String rawSql) {
        long start = System.nanoTime();
        
        // Step 1: Sanitize SQL (catch safety violations)
        String sanitizedSql;
        try {
            sanitizedSql = safetyPolicy.sanitizeAndEnforceLimit(rawSql);
        } catch (SqlSanitizationException e) {
            long ms = (System.nanoTime() - start) / 1_000_000L;
            String detailedMessage = String.format("[%s] %s", 
            e.getViolationType(), 
            e.getMessage()
            );
            return ExecuteSqlResponse.error(
                rawSql,
                ms,
                SqlExecutionError.fromSafetyViolation(detailedMessage)
            );
}

        // Step 2: Execute SQL (catch database errors)
        try {
            int maxRows = Math.max(1, props.getMaxLimit());
            int timeoutSec = Math.max(1, props.getQueryTimeoutSeconds());

            List<String> columns = new ArrayList<>();
            List<List<String>> rows = new ArrayList<>();
            boolean[] truncated = new boolean[] { false };

            jdbcTemplate.query(con -> {
                PreparedStatement ps = con.prepareStatement(sanitizedSql);
                ps.setQueryTimeout(timeoutSec);
                ps.setMaxRows(maxRows + 1);
                ps.setFetchSize(Math.min(200, maxRows));
                return ps;
            }, rs -> {
                var md = rs.getMetaData();
                int colCount = md.getColumnCount();
                columns.clear();
                for (int i = 1; i <= colCount; i++){
                    columns.add(md.getColumnLabel(i));
                }

                int count = 0;
                while (rs.next()) {
                    count++;
                    if (count > maxRows) {
                        truncated[0] = true;
                        break;
                    }
                    List<String> row = new ArrayList<>(colCount);
                    for (int i = 1; i <= colCount; i++) {
                        Object v = rs.getObject(i);
                        row.add(v == null ? null : String.valueOf(v));
                    }
                    rows.add(row);
                }

                return null;
            });
            
            long ms = (System.nanoTime() - start) / 1_000_000L;
            return ExecuteSqlResponse.success(
                    sanitizedSql,
                    columns,
                    rows,
                    truncated[0],
                    rows.size(),
                    ms
            );

        } catch (DataAccessException e) {
            long ms = (System.nanoTime() - start) / 1_000_000L;
            SQLException sqlEx = extractSqlException(e);
            SqlExecutionError error = mapSqlException(sqlEx);
            return ExecuteSqlResponse.error(sanitizedSql, ms, error);
        }
    }

    /**
     * Extract the root SQLException from Spring's DataAccessException.
     */
    private SQLException extractSqlException(DataAccessException e) {
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof SQLException sqlEx) {
                return sqlEx;
            }
            cause = cause.getCause();
        }
        return null;
    }

    /**
     * Map SQLException to normalized SqlExecutionError.
     * Uses PostgreSQL SQLSTATE codes for classification.
     */
    private SqlExecutionError mapSqlException(SQLException e) {
        if (e == null) {
            return SqlExecutionError.fromSqlException(
                    SqlErrorCode.EXECUTION_ERROR,
                    "Unknown database error",
                    null,
                    null,
                    null
            );
        }

        String sqlState = e.getSQLState();
        String message = e.getMessage();
        
        // Map SQLSTATE to error codes
        SqlErrorCode errorCode = classifySqlState(sqlState);

        // Try to extract position from PostgreSQL error message
        Integer position = extractPosition(message);

        return SqlExecutionError.fromSqlException(
                errorCode,
                message,
                sqlState,
                position,
                null
        );
    }

    /**
     * Classify PostgreSQL SQLSTATE code into normalized error code.
     * See: https://www.postgresql.org/docs/current/errcodes-appendix.html
     */
    private SqlErrorCode classifySqlState(String sqlState) {
        if (sqlState == null) {
            return SqlErrorCode.EXECUTION_ERROR;
        }

        return switch (sqlState) {
            // Syntax errors (Class 42 - Syntax Error or Access Rule Violation)
            case "42601" -> SqlErrorCode.SYNTAX_ERROR;          // syntax_error
            case "42P01" -> SqlErrorCode.TABLE_NOT_FOUND;       // undefined_table
            case "42703" -> SqlErrorCode.COLUMN_NOT_FOUND;      // undefined_column
            case "42702" -> SqlErrorCode.AMBIGUOUS_COLUMN;      // ambiguous_column
            case "42804" -> SqlErrorCode.TYPE_ERROR;            // datatype_mismatch
            case "42501" -> SqlErrorCode.PERMISSION_DENIED;     // insufficient_privilege

            // Data exceptions (Class 22)
            case "22012" -> SqlErrorCode.ARITHMETIC_ERROR;      // division_by_zero
            case "22003" -> SqlErrorCode.ARITHMETIC_ERROR;      // numeric_value_out_of_range
            case "22P02" -> SqlErrorCode.TYPE_ERROR;            // invalid_text_representation
            case "22007" -> SqlErrorCode.TYPE_ERROR;            // invalid_datetime_format

            // Query cancelled (Class 57)
            case "57014" -> SqlErrorCode.QUERY_CANCELLED;       // query_canceled (includes timeout)

            // Connection/database issues (Class 08, 53, 58)
            case "08006", "08003", "08001" -> SqlErrorCode.DATABASE_UNAVAILABLE;  // connection_failure
            case "53300" -> SqlErrorCode.DATABASE_UNAVAILABLE;  // too_many_connections

            // Default for unknown errors
            default -> {
                // Class-level matching
                if (sqlState.startsWith("42")) yield SqlErrorCode.SYNTAX_ERROR;
                if (sqlState.startsWith("22")) yield SqlErrorCode.TYPE_ERROR;
                if (sqlState.startsWith("57")) yield SqlErrorCode.QUERY_CANCELLED;
                if (sqlState.startsWith("08") || sqlState.startsWith("53")) 
                    yield SqlErrorCode.DATABASE_UNAVAILABLE;
                yield SqlErrorCode.EXECUTION_ERROR;
            }
        };
    }

    /**
     * Extract error position from PostgreSQL error message.
     * PostgreSQL format: "... at character 42"
     */
    private Integer extractPosition(String message) {
        if (message == null) return null;
        
        int idx = message.indexOf("at character ");
        if (idx == -1) return null;
        
        try {
            String posStr = message.substring(idx + 13).split("[^0-9]")[0];
            return Integer.parseInt(posStr);
        } catch (Exception e) {
            return null;
        }
    }
}