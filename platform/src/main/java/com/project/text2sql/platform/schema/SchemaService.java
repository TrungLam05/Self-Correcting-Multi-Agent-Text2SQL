package com.project.text2sql.platform.schema;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.project.text2sql.platform.config.Text2SqlSchemaProperties;
import com.project.text2sql.platform.schema.dto.SchemaSnapshotDto;
import com.project.text2sql.platform.schema.dto.TableSchemaDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SchemaService {
    private final PostgresSchemaRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final Text2SqlSchemaProperties schemaProperties;

    private final Cache<String, SchemaSnapshotDto> schemaCache;
    private final Cache<String, TableSchemaDto> tableCache;

    public SchemaService (PostgresSchemaRepository repository, JdbcTemplate jdbcTemplate, Text2SqlSchemaProperties schemaProperties) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
        this.schemaProperties = schemaProperties;

        this.schemaCache = Caffeine.newBuilder()
                .expireAfterWrite(java.time.Duration.ofSeconds(Math.max(1, schemaProperties.getCacheTtlSeconds())))
                .maximumSize(20)
                .build();

        this.tableCache = Caffeine.newBuilder()
                .expireAfterWrite(java.time.Duration.ofSeconds(Math.max(1, schemaProperties.getCacheTtlSeconds())))
                .maximumSize(200)
                .build();
    }

    public SchemaSnapshotDto getSchemaSnapshot(String schema) {
        String normalized = normalizeAndValidateSchema(schema);
        return schemaCache.get(normalized.toLowerCase(Locale.ROOT), s -> loadSnapshot(normalized));
    }

    public TableSchemaDto getTableSchema(String schema, String table) {
        String normalizedSchema = normalizeAndValidateSchema(schema);
        IdentifierValidator.requireValidIdentifier(table, "Table");
        String cacheKey = (normalizedSchema + "." + table).toLowerCase(Locale.ROOT);
        return tableCache.get(cacheKey, k -> repository.loadTableSchema(normalizedSchema, table));
    }

    private SchemaSnapshotDto loadSnapshot(String schema) {
        String dbName = jdbcTemplate.queryForObject("SELECT current_database()", String.class);
        Instant now = Instant.now();
        List<TableSchemaDto> tables = repository.loadSchema(schema);
        return new SchemaSnapshotDto(dbName, schema, now, schemaProperties.getCacheTtlSeconds(), tables);
    }

    private String normalizeAndValidateSchema(String schema) {
        String normalized = (schema == null || schema.isBlank()) ? "public" : schema.trim();
        IdentifierValidator.requireValidIdentifier(normalized, "Schema");

        if (schemaProperties.getAllowedSchemas() != null && !schemaProperties.getAllowedSchemas().isEmpty()) {
            boolean allowed = schemaProperties.getAllowedSchemas().stream().anyMatch(s -> s.equalsIgnoreCase(normalized));
            if (!allowed) {
                throw new IllegalArgumentException("Schema not allowed");
            }
        }

        return normalized;
    }
}