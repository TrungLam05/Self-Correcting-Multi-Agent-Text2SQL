package com.project.text2sql.platform.text2sql.dto;

import java.util.List;

public record Text2SqlResponse(
        String sql,
        List<String> columns,
        List<List<String>> rows,
        double confidence,
        String explanation,
        int attempts,
        long executionTimeMs,
        String traceId
) {}
