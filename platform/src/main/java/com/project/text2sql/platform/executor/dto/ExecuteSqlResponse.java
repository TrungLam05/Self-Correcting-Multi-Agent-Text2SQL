package com.project.text2sql.platform.executor.dto;

import java.util.List;

public record ExecuteSqlResponse(
        String sanitizedSql,
        List<String> columns,
        List<List<String>> rows,
        boolean truncated,
        int rowCount,
        long executionTimeMs
) {}