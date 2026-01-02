package com.project.text2sql.platform.executor.dto;

/**
 * Metadata about SQL repairs and sanitization performed by the system.
 * Provides transparency into how user's original SQL was modified.
 */
public record SqlRepairMetadata(
        String originalSql,
        String repairedSql,
        boolean wasRepaired,
        String repairReason
) {
    /**
     * Create metadata when no repair was needed.
     */
    public static SqlRepairMetadata noRepair(String sql) {
        return new SqlRepairMetadata(
                sql,
                sql,
                false,
                null
        );
    }
    
    /**
     * Create metadata when SQL was repaired.
     */
    public static SqlRepairMetadata repaired(String original, String repaired, String reason) {
        return new SqlRepairMetadata(
                original,
                repaired,
                true,
                reason
        );
    }
}