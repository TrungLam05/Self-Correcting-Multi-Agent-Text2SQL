package com.project.text2sql.platform.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.text2sql.platform.executor.dto.ExecuteSqlRequest;
import com.project.text2sql.platform.executor.dto.ExecuteSqlResponse;
import com.project.text2sql.platform.executor.dto.SqlRepairMetadata;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;

/**
 * Integration tests for SQL Executor API with structured error handling.
 * Tests the complete flow from HTTP request to error response.
 */
@WebMvcTest(SqlExecutorController.class)
class SqlExecutorControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private SqlExecutorService executorService;

    // =====================================================================
    // SUCCESS CASES
    // =====================================================================

    @Test
    void execute_validQuery_returns200WithData() throws Exception {
        // Arrange
        ExecuteSqlResponse mockResponse = ExecuteSqlResponse.success(
                "SELECT * FROM orders LIMIT 10",
                List.of("order_id", "total_amount"),
                List.of(
                        List.of("1", "100.00"),
                        List.of("2", "200.00")
                ),
                false,
                2,
                45,
                SqlRepairMetadata.noRepair("SELECT * FROM orders LIMIT 10"),
                0
        );
        
        when(executorService.execute(any())).thenReturn(mockResponse);

        ExecuteSqlRequest request = new ExecuteSqlRequest("SELECT * FROM orders LIMIT 10");

        // Act & Assert
        MvcResult result = mockMvc.perform(post("/api/executor/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.columns[0]").value("order_id"))
                .andExpect(jsonPath("$.columns[1]").value("total_amount"))
                .andExpect(jsonPath("$.rowCount").value(2))
                .andExpect(jsonPath("$.truncated").value(false))
                .andReturn();

        ExecuteSqlResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                ExecuteSqlResponse.class
        );

        assertNull(response.error());
        assertEquals(2, response.rowCount());
    }

    // =====================================================================
    // ERROR RESPONSES - STILL RETURN 200 OK
    // =====================================================================

    @Test
    void execute_syntaxError_returns200WithErrorDetails() throws Exception {
        // Arrange
        ExecuteSqlResponse mockResponse = ExecuteSqlResponse.error(
                "SELECTT * FROM orders",
                12,
                new com.project.text2sql.platform.executor.dto.SqlExecutionError(
                        SqlErrorCode.SYNTAX_ERROR,
                        "ERROR: syntax error at or near \"SELECTT\" at character 1",
                        "42601",
                        1,
                        null
                ),
                SqlRepairMetadata.noRepair("SELECTT * FROM orders"),
                0
        );
        
        when(executorService.execute(any())).thenReturn(mockResponse);

        ExecuteSqlRequest request = new ExecuteSqlRequest("SELECTT * FROM orders");

        // Act & Assert
        MvcResult result = mockMvc.perform(post("/api/executor/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())  // Still 200 OK!
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.error.errorCode").value("SYNTAX_ERROR"))
                .andExpect(jsonPath("$.error.sqlState").value("42601"))
                .andExpect(jsonPath("$.error.position").value(1))
                .andExpect(jsonPath("$.rowCount").value(0))
                .andExpect(jsonPath("$.columns").isEmpty())
                .andExpect(jsonPath("$.rows").isEmpty())
                .andReturn();

        ExecuteSqlResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                ExecuteSqlResponse.class
        );

        assertNotNull(response.error());
        assertEquals(SqlErrorCode.SYNTAX_ERROR, response.error().errorCode());
    }

    @Test
    void execute_tableNotFound_returns200WithErrorCode() throws Exception {
        // Arrange
        ExecuteSqlResponse mockResponse = ExecuteSqlResponse.error(
                "SELECT * FROM nonexistent_table",
                8,
                new com.project.text2sql.platform.executor.dto.SqlExecutionError(
                        SqlErrorCode.TABLE_NOT_FOUND,
                        "ERROR: relation \"nonexistent_table\" does not exist",
                        "42P01",
                        null,
                        null
                ),
                SqlRepairMetadata.noRepair("SELECT * FROM nonexistent_table"),
                0
        );
        
        when(executorService.execute(any())).thenReturn(mockResponse);

        ExecuteSqlRequest request = new ExecuteSqlRequest("SELECT * FROM nonexistent_table");

        // Act & Assert
        mockMvc.perform(post("/api/executor/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.errorCode").value("TABLE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.sqlState").value("42P01"))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("does not exist")));
    }

    @Test
    void execute_safetyViolation_returns200WithSafetyViolationCode() throws Exception {
        // Arrange
        ExecuteSqlResponse mockResponse = ExecuteSqlResponse.error(
                "DELETE FROM orders",
                2,
                new com.project.text2sql.platform.executor.dto.SqlExecutionError(
                        SqlErrorCode.SAFETY_VIOLATION,
                        "only SELECT statements are allowed",
                        null,
                        null,
                        null
                ),
                SqlRepairMetadata.noRepair("DELETE FROM orders"),
                0
        );
        
        when(executorService.execute(any())).thenReturn(mockResponse);

        ExecuteSqlRequest request = new ExecuteSqlRequest("DELETE FROM orders");

        // Act & Assert
        mockMvc.perform(post("/api/executor/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.errorCode").value("SAFETY_VIOLATION"))
                .andExpect(jsonPath("$.error.sqlState").doesNotExist())
                .andExpect(jsonPath("$.error.message").value("only SELECT statements are allowed"));
    }

    @Test
    void execute_queryTimeout_returns200WithTimeoutCode() throws Exception {
        // Arrange
        ExecuteSqlResponse mockResponse = ExecuteSqlResponse.error(
                "SELECT * FROM huge_table",
                5001,
                new com.project.text2sql.platform.executor.dto.SqlExecutionError(
                        SqlErrorCode.QUERY_CANCELLED,
                        "ERROR: canceling statement due to statement timeout",
                        "57014",
                        null,
                        null
                ),
                SqlRepairMetadata.noRepair("SELECT * FROM huge_table"),
                0
        );
        
        when(executorService.execute(any())).thenReturn(mockResponse);

        ExecuteSqlRequest request = new ExecuteSqlRequest("SELECT * FROM huge_table");

        // Act & Assert
        mockMvc.perform(post("/api/executor/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.errorCode").value("QUERY_CANCELLED"))
                .andExpect(jsonPath("$.error.sqlState").value("57014"));
    }

    // =====================================================================
    // VERIFY ERROR RESPONSE STRUCTURE
    // =====================================================================

    @Test
    void errorResponse_hasCorrectStructure() throws Exception {
        // Arrange
        ExecuteSqlResponse mockResponse = ExecuteSqlResponse.error(
                "SELECT bad_column FROM orders",
                15,
                new com.project.text2sql.platform.executor.dto.SqlExecutionError(
                        SqlErrorCode.COLUMN_NOT_FOUND,
                        "ERROR: column \"bad_column\" does not exist",
                        "42703",
                        15,
                        "Check column name spelling"
                ),
                SqlRepairMetadata.noRepair("SELECT bad_column FROM orders"),
                0
        );
        
        when(executorService.execute(any())).thenReturn(mockResponse);

        ExecuteSqlRequest request = new ExecuteSqlRequest("SELECT bad_column FROM orders");

        // Act & Assert
        mockMvc.perform(post("/api/executor/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sanitizedSql").value("SELECT bad_column FROM orders"))
                .andExpect(jsonPath("$.columns").isEmpty())
                .andExpect(jsonPath("$.rows").isEmpty())
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.rowCount").value(0))
                .andExpect(jsonPath("$.executionTimeMs").value(15))
                .andExpect(jsonPath("$.error.errorCode").value("COLUMN_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").exists())
                .andExpect(jsonPath("$.error.sqlState").value("42703"))
                .andExpect(jsonPath("$.error.position").value(15))
                .andExpect(jsonPath("$.error.detail").value("Check column name spelling"));
    }
}