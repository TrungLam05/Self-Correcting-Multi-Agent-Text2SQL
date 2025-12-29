package com.project.text2sql.platform.schema.dto;

import java.util.List;

public record ForeignKeySchemaDto(
        String name,
        List<String> columns,
        String referencesSchema,
        String referencesTable,
        List<String> referencesColumns) {
}