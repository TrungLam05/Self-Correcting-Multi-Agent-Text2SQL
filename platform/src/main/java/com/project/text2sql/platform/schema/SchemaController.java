package com.project.text2sql.platform.schema;

import com.project.text2sql.platform.schema.dto.SchemaSnapshotDto;
import com.project.text2sql.platform.schema.dto.TableSchemaDto;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/schema")
public class SchemaController {
    private final SchemaService schemaService;

    public SchemaController(SchemaService schemaService) {
        this.schemaService = schemaService;
    }

    @GetMapping
    public SchemaSnapshotDto getSchemaSnapshot(@RequestParam(name = "schema", required = false) String schema) {
        return schemaService.getSchemaSnapshot(schema);
    }

    @GetMapping("/tables/{schema}/{table}")
    public TableSchemaDto getTableSchema(@PathVariable String schema, @PathVariable String table) {
        TableSchemaDto out = schemaService.getTableSchema(schema, table);
        if (out == null) {
            throw new TableNotFoundException();
        }
        return out;
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    private static final class TableNotFoundException extends RuntimeException {
        private TableNotFoundException() {
            super("Table not found");
        }
    }
}
