package com.project.text2sql.platform.schema.dto;

import java.util.List;

public record TableSchemaDto(
        String schema,
        String name,
        String comment,
        List<ColumnSchemaDto> columns,
        List<String> primaryKeyColumns,
        List<ForeignKeySchemaDto> foreignKeys,
        List<IndexSchemaDto> indexes) {
}