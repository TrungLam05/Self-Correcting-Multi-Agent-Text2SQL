package com.project.text2sql.platform.executor;

/**
 * Exception thrown when SQL fails safety validation.
 * Used to distinguish safety violations from other errors.
 */
public class SqlSanitizationException extends RuntimeException {
    
    private final String sqlSnippet;
    private final ViolationType violationType;
    
    public enum ViolationType {
        INVALID_SQL,
        FORBIDDEN_STATEMENT_TYPE,
        MULTIPLE_STATEMENTS,
        EXCEEDS_LENGTH_LIMIT,
        LOCKING_CLAUSE_DETECTED,
        PARSE_ERROR
    }
    
    public SqlSanitizationException(String message, ViolationType violationType) {
        super(message);
        this.violationType = violationType;
        this.sqlSnippet = null;
    }
    
    public SqlSanitizationException(String message, ViolationType violationType, String sqlSnippet) {
        super(message);
        this.violationType = violationType;
        this.sqlSnippet = sqlSnippet;
    }
    
    public SqlSanitizationException(String message, ViolationType violationType, Throwable cause) {
        super(message, cause);
        this.violationType = violationType;
        this.sqlSnippet = null;
    }
    
    public ViolationType getViolationType() {
        return violationType;
    }
    
    public String getSqlSnippet() {
        return sqlSnippet;
    }
}