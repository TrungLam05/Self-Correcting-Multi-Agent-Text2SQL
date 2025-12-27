package com.project.text2sql.platform.text2sql;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.project.text2sql.platform.executor.SqlExecutorService;
import com.project.text2sql.platform.executor.dto.ExecuteSqlResponse;
import com.project.text2sql.platform.text2sql.dto.Text2SqlResponse;

@Service
public class Text2SqlService {
    private final SqlExecutorService sqlExecutorService;

    public Text2SqlService(SqlExecutorService sqlExecutorService) {
        this.sqlExecutorService = sqlExecutorService;
    }

    public Text2SqlResponse executeQuestion(String question) {
        String traceId = UUID.randomUUID().toString();
        String trimmed = question == null ? "" : question.trim();

        // Until the Text2SQL generation pipeline (router/planner/generator/repair/verifier) is implemented,
        // support a pragmatic "SQL passthrough" mode for serverless REST integration testing.
        if (looksLikeSql(trimmed)) {
            ExecuteSqlResponse exec = sqlExecutorService.execute(trimmed);
            return new Text2SqlResponse(
                    exec.sanitizedSql(),
                    exec.columns(),
                    exec.rows(),
                    1.0,
                    "Executed provided SQL safely (SELECT-only, timeout, LIMIT enforced).",
                    1,
                    exec.executionTimeMs(),
                    traceId
            );
        }

        return new Text2SqlResponse(
                null,
                List.of(),
                List.of(),
                0.0,
                "Text2SQL generation is not implemented yet. Provide a SELECT query in 'question' to run in passthrough mode.",
                0,
                0L,
                traceId
        );
    }

    private static boolean looksLikeSql(String s) {
        if (s == null) return false;
        String lower = s.stripLeading().toLowerCase();
        return lower.startsWith("select ") || lower.equals("select") || lower.startsWith("with ");
    }
}
