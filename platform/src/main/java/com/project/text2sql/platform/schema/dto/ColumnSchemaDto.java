package com.project.text2sql.platform.schema.dto;

public record ColumnSchemaDto(
        String name,
        String dataType,
        boolean nullable, 
        String defaultValue,
        String comment) {
}