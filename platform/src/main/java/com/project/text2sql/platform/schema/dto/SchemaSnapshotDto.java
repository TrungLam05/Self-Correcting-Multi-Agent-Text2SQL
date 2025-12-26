package com.project.text2sql.platform.schema.dto;

import java.time.Instant;
import java.util.List;

public record SchemaSnapshotDto(
        String database,
        String schema,
        Instant generatedAt,
        int ttlSeconds,
        List<TableSchemaDto> tables) {
}