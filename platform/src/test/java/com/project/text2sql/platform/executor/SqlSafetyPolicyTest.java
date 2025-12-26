package com.project.text2sql.platform.executor;

import com.project.text2sql.platform.config.Text2SqlExecutorProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SqlSafetyPolicyTest {

    @Test
    void rejectsNonSelect() {
        SqlSafetyPolicy p = new SqlSafetyPolicy(props());
        assertThrows(IllegalArgumentException.class, () -> p.sanitizeAndEnforceLimit("DELETE FROM users"));
        assertThrows(IllegalArgumentException.class, () -> p.sanitizeAndEnforceLimit("UPDATE users SET country='US'"));
        assertThrows(IllegalArgumentException.class, () -> p.sanitizeAndEnforceLimit("CREATE TABLE x(id int)"));
    }

    @Test
    void rejectsMultipleStatements() {
        SqlSafetyPolicy p = new SqlSafetyPolicy(props());
        assertThrows(IllegalArgumentException.class, () -> p.sanitizeAndEnforceLimit("SELECT 1; SELECT 2;"));
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