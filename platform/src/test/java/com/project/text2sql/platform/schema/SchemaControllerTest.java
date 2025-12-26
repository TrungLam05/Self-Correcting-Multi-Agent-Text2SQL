package com.project.text2sql.platform.schema;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import com.project.text2sql.platform.schema.dto.ColumnSchemaDto;
import com.project.text2sql.platform.schema.dto.SchemaSnapshotDto;
import com.project.text2sql.platform.schema.dto.TableSchemaDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = SchemaController.class)
class SchemaControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SchemaService schemaService;

    @Test
    void getSchemaSnapshot_defaultsToPublic() throws Exception {
        SchemaSnapshotDto dto = new SchemaSnapshotDto(
                "analytics",
                "public",
                Instant.ofEpochMilli(1234),
                300,
                List.of()
        );
        when(schemaService.getSchemaSnapshot(eq(null))).thenReturn(dto);

        mockMvc.perform(get("/api/schema"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.database").value("analytics"))
                .andExpect(jsonPath("$.schema").value("public"))
                .andExpect(jsonPath("$.ttlSeconds").value(300));
    }

    @Test
    void getTableSchema_bindsSchemaAndTable() throws Exception {
        TableSchemaDto dto = new TableSchemaDto(
                "public",
                "users",
                null,
                List.of(new ColumnSchemaDto("id", "int8", false, null, null)),
                List.of("id"),
                List.of(),
                List.of()
        );
        when(schemaService.getTableSchema(eq("public"), eq("users"))).thenReturn(dto);

        mockMvc.perform(get("/api/schema/tables/public/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schema").value("public"))
                .andExpect(jsonPath("$.name").value("users"))
                .andExpect(jsonPath("$.columns[0].name").value("id"));
    }

    @Test
    void getTableSchema_returns404WhenMissing() throws Exception {
        when(schemaService.getTableSchema(eq("public"), eq("does_not_exist"))).thenReturn(null);

        mockMvc.perform(get("/api/schema/tables/public/does_not_exist"))
                .andExpect(status().isNotFound());
    }
}
