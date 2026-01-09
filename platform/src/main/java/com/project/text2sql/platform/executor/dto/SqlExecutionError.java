package com.project.text2sql.platform.executor.dto;

import com.project.text2sql.platform.executor.SqlErrorCode;

/**
 * Structured error information for SQL execution failures.
 * Allows agents to programmatically handle different error scenarios.
 */
public record SqlExecutionError(
        SqlErrorCode errorCode,
        String message,
        String sqlState,
        Integer position,
        String detail
) {
    /**
     * Create error from exception message only (for safety violations).
     */
    public static SqlExecutionError fromSafetyViolation(String message) {
        return new SqlExecutionError(
                SqlErrorCode.SAFETY_VIOLATION,
                message,
                null,
                null,
                null
        );
    }

    /**
     * Create error from SQL exception with full details.
     */
    public static SqlExecutionError fromSqlException(
            SqlErrorCode errorCode,
            String message,
            String sqlState,
            Integer position,
            String detail
    ) {
        return new SqlExecutionError(errorCode, message, sqlState, position, detail);
    }
}