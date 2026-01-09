package com.project.text2sql.platform.executor;

/**
 * Normalized error codes for SQL execution failures.
 * Allows agents to reason about failures programmatically.
 */
public enum SqlErrorCode {
    /** SQL syntax error (PostgreSQL SQLSTATE 42xxx) */
    SYNTAX_ERROR,
    
    /** Referenced table does not exist (SQLSTATE 42P01) */
    TABLE_NOT_FOUND,
    
    /** Referenced column does not exist (SQLSTATE 42703) */
    COLUMN_NOT_FOUND,
    
    /** Insufficient permissions (SQLSTATE 42501) */
    PERMISSION_DENIED,
    
    /** Query exceeded timeout limit (SQLSTATE 57014) */
    QUERY_TIMEOUT,
    
    /** Query cancelled by user or system (SQLSTATE 57014) */
    QUERY_CANCELLED,
    
    /** Invalid SQL rejected by safety policy */
    SAFETY_VIOLATION,
    
    /** Division by zero or arithmetic error (SQLSTATE 22xxx) */
    ARITHMETIC_ERROR,
    
    /** Data type mismatch or conversion error (SQLSTATE 22xxx, 42804) */
    TYPE_ERROR,
    
    /** Generic execution error */
    EXECUTION_ERROR,
    
    /** Ambiguous column reference (SQLSTATE 42702) */
    AMBIGUOUS_COLUMN,
    
    /** Connection or database unavailable */
    DATABASE_UNAVAILABLE
}
