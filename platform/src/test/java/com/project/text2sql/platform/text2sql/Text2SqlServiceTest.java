package com.project.text2sql.platform.text2sql;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.project.text2sql.platform.executor.SqlExecutorService;
import com.project.text2sql.platform.executor.dto.ExecuteSqlResponse;

class Text2SqlServiceTest {

    @Test
    void passthrough_executesSqlWhenQuestionLooksLikeSql() {
        SqlExecutorService exec = mock(SqlExecutorService.class);
        when(exec.execute(anyString())).thenReturn(new ExecuteSqlResponse(
                "SELECT 1 LIMIT 200",
                List.of("?column?"),
                List.of(List.of("1")),
                false,
                1,
                10L
        ));

        Text2SqlService svc = new Text2SqlService(exec);
        var resp = svc.executeQuestion("SELECT 1");

        assertEquals("SELECT 1 LIMIT 200", resp.sql());
        assertEquals(1, resp.attempts());
        assertTrue(resp.confidence() > 0.0);
        assertNotNull(resp.traceId());
    }

    @Test
    void nonSql_returnsNotImplementedMessage() {
        SqlExecutorService exec = mock(SqlExecutorService.class);
        Text2SqlService svc = new Text2SqlService(exec);

        var resp = svc.executeQuestion("weekly active users by country");

        assertNull(resp.sql());
        assertEquals(0, resp.attempts());
        verifyNoInteractions(exec);
    }
}
