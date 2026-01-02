package com.project.text2sql.platform.executor.dto;

import java.util.List;

public record ExecuteSqlResponse(
        String sanitizedSql,
        List<String> columns,
        List<List<String>> rows,
        boolean truncated,
        int rowCount,
        long executionTimeMs,
        SqlExecutionError error,  // null if success, populated on error
        SqlRepairMetadata repairMetadata,
        int retryCount
) {
    /**
     * Create a successful response.
     */
    public static ExecuteSqlResponse success(
            String sanitizedSql,
            List<String> columns,
            List<List<String>> rows,
            boolean truncated,
            int rowCount,
            long executionTimeMs,
            SqlRepairMetadata repairMetadata,
            int retryCount
    ) {
        return new ExecuteSqlResponse(
                sanitizedSql,
                columns,
                rows,
                truncated,
                rowCount,
                executionTimeMs,
                null,  // No error
                repairMetadata,
                retryCount
        );
    }

    /**
     * Create an error response.
     */
    public static ExecuteSqlResponse error(
            String sanitizedSql,
            long executionTimeMs,
            SqlExecutionError error,
            SqlRepairMetadata repairMetadata,
            int retryCount
    ) {
        return new ExecuteSqlResponse(
                sanitizedSql,
                List.of(),  // Empty columns
                List.of(),  // Empty rows
                false,
                0,
                executionTimeMs,
                error,
                repairMetadata,
                retryCount
        );
    }
}