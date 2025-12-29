package com.project.text2sql.platform.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import com.project.text2sql.platform.config.Text2SqlSchemaProperties;
import com.project.text2sql.platform.schema.dto.ColumnSchemaDto;
import com.project.text2sql.platform.schema.dto.SchemaSnapshotDto;
import com.project.text2sql.platform.schema.dto.TableSchemaDto;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

class SchemaServiceTest {

    @Test
    void getSchemaSnapshot_cachesBySchema() {
        PostgresSchemaRepository repository = Mockito.mock(PostgresSchemaRepository.class);
        JdbcTemplate jdbcTemplate = Mockito.mock(JdbcTemplate.class);

        Text2SqlSchemaProperties props = new Text2SqlSchemaProperties();
        props.setCacheTtlSeconds(300);
        props.setAllowedSchemas(List.of("public"));

        List<TableSchemaDto> tables = List.of(
                new TableSchemaDto(
                        "public",
                        "users",
                        null,
                        List.of(new ColumnSchemaDto("id", "int8", false, null, null)),
                        List.of("id"),
                        List.of(),
                        List.of()
                )
        );

        when(jdbcTemplate.queryForObject(anyString(), Mockito.eq(String.class))).thenReturn("analytics");
        when(repository.loadSchema("public")).thenReturn(tables);

        SchemaService service = new SchemaService(repository, jdbcTemplate, props);

        SchemaSnapshotDto a = service.getSchemaSnapshot("public");
        SchemaSnapshotDto b = service.getSchemaSnapshot("public");

        assertThat(a.database()).isEqualTo("analytics");
        assertThat(b.database()).isEqualTo("analytics");
        verify(repository, times(1)).loadSchema("public");
    }

    @Test
    void getTableSchema_cachesPerSchemaTable() {
        PostgresSchemaRepository repository = Mockito.mock(PostgresSchemaRepository.class);
        JdbcTemplate jdbcTemplate = Mockito.mock(JdbcTemplate.class);

        Text2SqlSchemaProperties props = new Text2SqlSchemaProperties();
        props.setCacheTtlSeconds(300);
        props.setAllowedSchemas(List.of("public"));

        TableSchemaDto users = new TableSchemaDto(
                "public",
                "users",
                null,
                List.of(new ColumnSchemaDto("id", "int8", false, null, null)),
                List.of("id"),
                List.of(),
                List.of()
        );

        when(repository.loadTableSchema("public", "users")).thenReturn(users);

        SchemaService service = new SchemaService(repository, jdbcTemplate, props);

        TableSchemaDto a = service.getTableSchema("public", "users");
        TableSchemaDto b = service.getTableSchema("public", "users");

        assertThat(a).isNotNull();
        assertThat(b).isNotNull();
        verify(repository, times(1)).loadTableSchema("public", "users");
    }

    @Test
    void rejectsDisallowedSchema() {
        PostgresSchemaRepository repository = Mockito.mock(PostgresSchemaRepository.class);
        JdbcTemplate jdbcTemplate = Mockito.mock(JdbcTemplate.class);

        Text2SqlSchemaProperties props = new Text2SqlSchemaProperties();
        props.setCacheTtlSeconds(300);
        props.setAllowedSchemas(List.of("public"));

        SchemaService service = new SchemaService(repository, jdbcTemplate, props);

        assertThatThrownBy(() -> service.getSchemaSnapshot("private"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Schema not allowed");
    }
}
