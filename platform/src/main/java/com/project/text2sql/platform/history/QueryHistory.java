package com.project.text2sql.platform.history;

import java.time.LocalDateTime;

public record QueryHistory(
        Integer id,
        String naturalLanguageQuery,
        String generatedSql,
        String status,
        Integer executionTimeMs,
        LocalDateTime createdAt
) {}