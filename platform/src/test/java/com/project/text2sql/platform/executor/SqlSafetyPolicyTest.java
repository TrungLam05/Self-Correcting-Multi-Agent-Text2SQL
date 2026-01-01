package com.project.text2sql.platform.executor;

import com.project.text2sql.platform.config.Text2SqlExecutorProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SqlSafetyPolicyTest {

    @Test
    void rejectsNonSelect() {
        SqlSafetyPolicy p = new SqlSafetyPolicy(props());
        
        // DELETE
        SqlSanitizationException ex1 = assertThrows(
            SqlSanitizationException.class, 
            () -> p.sanitizeAndEnforceLimit("DELETE FROM users")
        );
        assertEquals(SqlSanitizationException.ViolationType.FORBIDDEN_STATEMENT_TYPE, ex1.getViolationType());
        
        // UPDATE
        SqlSanitizationException ex2 = assertThrows(
            SqlSanitizationException.class, 
            () -> p.sanitizeAndEnforceLimit("UPDATE users SET country='US'")
        );
        assertEquals(SqlSanitizationException.ViolationType.FORBIDDEN_STATEMENT_TYPE, ex2.getViolationType());
        
        // CREATE
        SqlSanitizationException ex3 = assertThrows(
            SqlSanitizationException.class, 
            () -> p.sanitizeAndEnforceLimit("CREATE TABLE x(id int)")
        );
        assertEquals(SqlSanitizationException.ViolationType.FORBIDDEN_STATEMENT_TYPE, ex3.getViolationType());
    }

    @Test
    void rejectsMultipleStatements() {
        SqlSafetyPolicy p = new SqlSafetyPolicy(props());
        
        SqlSanitizationException ex = assertThrows(
            SqlSanitizationException.class, 
            () -> p.sanitizeAndEnforceLimit("SELECT 1; SELECT 2;")
        );
        assertEquals(SqlSanitizationException.ViolationType.MULTIPLE_STATEMENTS, ex.getViolationType());
    }

    @Test
    void addsLimitIfMissing() {
        SqlSafetyPolicy p = new SqlSafetyPolicy(props());
        String out = p.sanitizeAndEnforceLimit("SELECT * FROM users");
        assertTrue(out.toLowerCase().contains("limit"));
    }

    private static Text2SqlExecutorProperties props() {
        Text2SqlExecutorProperties pr = new Text2SqlExecutorProperties();
        pr.setDefaultLimit(10);
        pr.setMaxLimit(100);
        pr.setMaxSqlLength(5000);
        pr.setQueryTimeoutSeconds(2);
        return pr;
    }
}