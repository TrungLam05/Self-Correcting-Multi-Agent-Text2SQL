package com.project.text2sql.platform.schema.dto;

import java.util.List;

public record IndexSchemaDto(
        String name,
        boolean unique,
        List<String> columns) {
}