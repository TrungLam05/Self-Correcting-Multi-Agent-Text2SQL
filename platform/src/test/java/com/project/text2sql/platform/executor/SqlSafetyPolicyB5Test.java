package com.project.text2sql.platform.executor;

import com.project.text2sql.platform.config.Text2SqlExecutorProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SqlSafetyPolicyB5Test {

    private SqlSafetyPolicy policy;

    @BeforeEach
    void setUp() {
        Text2SqlExecutorProperties props = new Text2SqlExecutorProperties();
        props.setMaxSqlLength(20000);
        props.setDefaultLimit(200);
        props.setMaxLimit(1000);
        policy = new SqlSafetyPolicy(props);
    }

    @Test
    void sanitize_validSelect_addsLimit() {
        String result = policy.sanitizeAndEnforceLimit("SELECT * FROM users");
        assertTrue(result.contains("LIMIT 200"));
    }

    @Test
    void sanitize_insertStatement_throwsException() {
        SqlSanitizationException ex = assertThrows(
            SqlSanitizationException.class,
            () -> policy.sanitizeAndEnforceLimit("INSERT INTO users VALUES (1)")
        );
        assertEquals(SqlSanitizationException.ViolationType.FORBIDDEN_STATEMENT_TYPE, ex.getViolationType());
    }

    @Test
    void sanitize_deleteStatement_throwsException() {
        SqlSanitizationException ex = assertThrows(
            SqlSanitizationException.class,
            () -> policy.sanitizeAndEnforceLimit("DELETE FROM users")
        );
        assertEquals(SqlSanitizationException.ViolationType.FORBIDDEN_STATEMENT_TYPE, ex.getViolationType());
    }

    @Test
    void sanitize_multipleStatements_throwsException() {
        SqlSanitizationException ex = assertThrows(
            SqlSanitizationException.class,
            () -> policy.sanitizeAndEnforceLimit("SELECT 1; DROP TABLE users;")
        );
        assertEquals(SqlSanitizationException.ViolationType.MULTIPLE_STATEMENTS, ex.getViolationType());
    }

    @Test
    void sanitize_forUpdate_throwsException() {
        SqlSanitizationException ex = assertThrows(
            SqlSanitizationException.class,
            () -> policy.sanitizeAndEnforceLimit("SELECT * FROM users FOR UPDATE")
        );
        assertEquals(SqlSanitizationException.ViolationType.LOCKING_CLAUSE_DETECTED, ex.getViolationType());
    }

    @Test
    void sanitize_blankSql_throwsException() {
        SqlSanitizationException ex = assertThrows(
            SqlSanitizationException.class,
            () -> policy.sanitizeAndEnforceLimit("")
        );
        assertEquals(SqlSanitizationException.ViolationType.INVALID_SQL, ex.getViolationType());
    }

    @Test
    void sanitize_limitAboveMax_clampsToMax() {
        String result = policy.sanitizeAndEnforceLimit("SELECT * FROM users LIMIT 5000");
        assertTrue(result.contains("LIMIT 1000"));
    }
}